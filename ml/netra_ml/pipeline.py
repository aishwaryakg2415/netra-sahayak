"""End-to-end pipeline: data -> leakage-safe splits -> train/compare -> calibrate ->
evaluate -> export LiteRT variants -> verify -> package Android assets.

Designed for a Kaggle / Colab GPU session:

    python -m netra_ml.pipeline --input /kaggle/input --out /kaggle/working/out

Every number written to the reports comes from an actual run of this code.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from multiprocessing import Pool

import numpy as np

from . import quality as Q
from .calibration import fit_temperature
from .datasets import UNGRADABLE, Record, discover_roots, load_all
from .metrics import (CLASS_NAMES, expected_calibration_error, full_report, referable_score, softmax,
                      threshold_for_sensitivity)
from .preprocess import OUT_SIZE, load_rgb, preprocess
from .referral import decide
from .splits import dhash, grouped_stratified_split, near_duplicate_pairs

SEED = 42
REFERABLE_MIN_GRADE = 2
TARGET_REFERABLE_SENSITIVITY = 0.90
TARGET_URGENT_SENSITIVITY = 0.90
REVIEW_MIN_CONFIDENCE = 0.50

EXPERIMENTS = [
    {"name": "effnetv2b0_ce", "arch": "effnetv2b0", "size": OUT_SIZE, "loss": "ce",
     "epochs": 10, "batch_size": 32, "lr": 3e-4},
    {"name": "mobilenetv3l_ce", "arch": "mobilenetv3l", "size": OUT_SIZE, "loss": "ce",
     "epochs": 10, "batch_size": 32, "lr": 5e-4},
    {"name": "effnetv2b0_focal", "arch": "effnetv2b0", "size": OUT_SIZE, "loss": "focal",
     "epochs": 10, "batch_size": 32, "lr": 3e-4},
    {"name": "effnetv2b2_ce", "arch": "effnetv2b2", "size": OUT_SIZE, "loss": "ce",
     "epochs": 8, "batch_size": 24, "lr": 2e-4},
]

_LOG_FH = None


def log(msg: str) -> None:
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    if _LOG_FH:
        _LOG_FH.write(line + "\n")
        _LOG_FH.flush()


# ------------------------------------------------------------------ caching

def _cache_one(args):
    idx, rec, cache_dir, fmt = args
    from PIL import Image
    try:
        rgb = load_rgb(rec.path)
        result, pre = Q.assess(rgb)
        if pre is None:
            return idx, None, None, None, "no_retina"
        ext = "png" if fmt == "png" else "jpg"
        path = os.path.join(cache_dir, f"{idx:06d}.{ext}")
        im = Image.fromarray(pre.image)
        if ext == "png":
            im.save(path, compress_level=1)
        else:
            im.save(path, quality=95, subsampling=0)
        gray = (pre.image.astype(np.uint16).sum(axis=2) // 3).astype(np.uint8)
        return idx, path, result.metrics.as_dict(), dhash(gray), None
    except Exception as exc:  # recorded per image, never silently dropped
        return idx, None, None, None, f"error:{type(exc).__name__}:{exc}"


def build_cache(records: list[Record], cache_dir: str, fmt: str) -> list[dict]:
    os.makedirs(cache_dir, exist_ok=True)
    rows = [None] * len(records)
    jobs = [(i, r, cache_dir, fmt) for i, r in enumerate(records)]
    t0 = time.time()
    with Pool(os.cpu_count()) as pool:
        for n, (i, path, metrics, h, err) in enumerate(pool.imap_unordered(_cache_one, jobs, chunksize=16)):
            rows[i] = {"cache": path, "metrics": metrics, "dhash": h, "error": err}
            if n % 5000 == 0:
                log(f"  cached {n}/{len(records)} ({time.time() - t0:.0f}s)")
    log(f"  cache done in {time.time() - t0:.0f}s")
    return rows


# ------------------------------------------------------------------- splits

def assign_splits(records: list[Record], cache_rows: list[dict]) -> list[str]:
    split = [""] * len(records)
    pool_idx = [i for i, r in enumerate(records)
                if r.dataset in ("aptos2019", "eyepacs") or (r.dataset == "ddr" and r.source_split in ("train", "valid"))]
    pool_idx = [i for i in pool_idx if records[i].label != UNGRADABLE]
    assigned = grouped_stratified_split([records[i].label for i in pool_idx],
                                        [records[i].patient_id for i in pool_idx],
                                        {"train": 0.8, "val": 0.1, "test_internal": 0.1}, seed=SEED)
    for i, s in zip(pool_idx, assigned):
        split[i] = s
    for i, r in enumerate(records):
        if split[i]:
            continue
        if r.label == UNGRADABLE:
            split[i] = f"ungradable_{r.dataset}"
        elif r.dataset == "ddr" and r.source_split == "test":
            split[i] = "test_ddr"
        elif r.dataset == "idrid":
            split[i] = "test_idrid"
        elif r.dataset == "messidor2":
            split[i] = "test_messidor2"
    for i, row in enumerate(cache_rows):
        if row["cache"] is None:
            split[i] = "excluded_" + (row["error"] or "unknown").split(":")[0]
    return split


def remove_leakage(records, cache_rows, split) -> dict:
    idx = [i for i, row in enumerate(cache_rows) if row["dhash"] is not None]
    pairs = near_duplicate_pairs([cache_rows[i]["dhash"] for i in idx])
    dropped = 0
    cross_eval = 0
    for a, b in pairs:
        i, j = idx[a], idx[b]
        si, sj = split[i], split[j]
        if si == sj:
            continue
        if si == "train" and not sj.startswith(("excluded", "dropped")):
            split[i] = "dropped_near_duplicate"
            dropped += 1
        elif sj == "train" and not si.startswith(("excluded", "dropped")):
            split[j] = "dropped_near_duplicate"
            dropped += 1
        elif not si.startswith(("excluded", "dropped")) and not sj.startswith(("excluded", "dropped")):
            cross_eval += 1
    return {"near_duplicate_pairs": len(pairs), "train_images_dropped": dropped,
            "pairs_between_evaluation_sets": cross_eval}


# ------------------------------------------------------------------ helpers

def choose_temperature(logits: np.ndarray, y: np.ndarray, seed: int = SEED) -> tuple[float, dict]:
    """Temperature scaling is kept only if it improves ECE out-of-fold.

    2-fold cross-fitting on the validation split: fit T on one half, measure ECE
    on the other half, both ways. If the average ECE does not improve, T = 1.
    """
    rng = np.random.default_rng(seed)
    idx = rng.permutation(len(y))
    folds = [idx[: len(y) // 2], idx[len(y) // 2:]]
    before, after = [], []
    for a, b in ((0, 1), (1, 0)):
        t = fit_temperature(logits[folds[a]], y[folds[a]])
        before.append(expected_calibration_error(softmax(logits[folds[b]]), y[folds[b]]))
        after.append(expected_calibration_error(softmax(logits[folds[b]], t), y[folds[b]]))
    improves = float(np.mean(after)) < float(np.mean(before))
    T = fit_temperature(logits, y) if improves else 1.0
    return T, {"out_of_fold_ece_uncalibrated": float(np.mean(before)),
               "out_of_fold_ece_calibrated": float(np.mean(after)), "applied": improves}


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def subset(records, cache_rows, split, name, gate=None):
    sel = [i for i in range(len(records)) if split[i] == name]
    if gate is not None:
        sel = [i for i in sel if gate[i] == Q.ACCEPTED]
    return [cache_rows[i]["cache"] for i in sel], np.array([records[i].label for i in sel], np.int64), sel


def jsonable(o):
    if isinstance(o, dict):
        return {str(k): jsonable(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [jsonable(v) for v in o]
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.floating,)):
        return None if np.isnan(o) else float(o)
    if isinstance(o, float) and np.isnan(o):
        return None
    if isinstance(o, np.ndarray):
        return jsonable(o.tolist())
    return o


def dump(obj, path):
    with open(path, "w") as fh:
        json.dump(jsonable(obj), fh, indent=2)


# --------------------------------------------------------------------- main

def main(argv=None) -> None:
    global _LOG_FH
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", default="/kaggle/input")
    ap.add_argument("--out", default="/kaggle/working/out")
    ap.add_argument("--cache", default="/tmp/netra_cache")
    ap.add_argument("--train-budget-hours", type=float, default=7.5)
    ap.add_argument("--experiments", default="all", help="comma-separated names or 'all'")
    ap.add_argument("--max-per-split", type=int, default=0, help="smoke test: cap images per dataset")
    ap.add_argument("--epochs-override", type=int, default=0, help="smoke test: epochs per experiment")
    args = ap.parse_args(argv)

    t_start = time.time()
    os.makedirs(args.out, exist_ok=True)
    reports = os.path.join(args.out, "reports")
    assets = os.path.join(args.out, "android_assets")
    golden = os.path.join(args.out, "golden")
    for d in (reports, assets, golden):
        os.makedirs(d, exist_ok=True)
    _LOG_FH = open(os.path.join(reports, "pipeline.log"), "a")

    # TensorFlow is imported only after the multiprocessing cache step: forking a
    # process that has already initialised TF can deadlock.
    free_gb = shutil.disk_usage(os.path.dirname(args.cache) or "/").free / 1e9
    log(f"free disk for cache: {free_gb:.1f} GB; CPUs: {os.cpu_count()}")

    # 1. data
    roots = discover_roots(args.input)
    log(f"dataset roots: {json.dumps(roots, indent=1)}")
    data = load_all(roots)
    records: list[Record] = [r for k in sorted(data) for r in data[k]]
    for k, v in data.items():
        log(f"  {k}: {len(v)} images, labels {np.bincount([r.label for r in v], minlength=6).tolist()}")
    if args.max_per_split:
        rng = np.random.default_rng(SEED)
        keep = []
        for k in sorted(data):
            items = data[k]
            pick = rng.permutation(len(items))[: args.max_per_split]
            keep += [items[i] for i in sorted(pick)]
        records = keep
        log(f"DEBUG cap: {len(records)} images")

    fmt = "png" if free_gb > 30 else "jpg"
    log(f"caching preprocessed {OUT_SIZE}px images as {fmt}")
    cache_rows = build_cache(records, args.cache, fmt)

    # 2. splits + leakage
    split = assign_splits(records, cache_rows)
    leakage = remove_leakage(records, cache_rows, split)
    log(f"leakage control: {leakage}")

    # 3. quality thresholds (fitted on train only)
    train_metrics = [Q.QualityMetrics(**cache_rows[i]["metrics"]) for i in range(len(records))
                     if split[i] == "train"]
    thresholds = Q.fit_thresholds(train_metrics)
    gate = []
    for i, row in enumerate(cache_rows):
        if row["metrics"] is None:
            gate.append(Q.UNSUPPORTED)
        else:
            gate.append(Q.decide(Q.QualityMetrics(**row["metrics"]), thresholds).status)
    split_names = sorted(set(split))
    gate_report = {}
    for s in split_names:
        sel = [i for i in range(len(records)) if split[i] == s]
        gate_report[s] = {"n": len(sel), **{st: sum(gate[i] == st for i in sel) for st in (Q.ACCEPTED, Q.RETAKE, Q.UNSUPPORTED)}}
    log(f"quality thresholds: {thresholds}")
    log(f"quality gate by split: {json.dumps(gate_report, indent=1)}")
    dump({"thresholds": thresholds, "by_split": gate_report,
          "note": "Thresholds = 0.5th/99.5th percentiles of curated training images. "
                  "Rejection rates on 'ungradable_*' rows estimate sensitivity to ungradable images."},
         os.path.join(reports, "quality_gate.json"))

    with open(os.path.join(reports, "split_manifest.csv"), "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["dataset", "source_path", "label", "patient_id", "source_split", "split", "quality_status", "dhash"])
        for r, row, s, g in zip(records, cache_rows, split, gate):
            w.writerow([r.dataset, r.path.replace(args.input, "<input>"), r.label, r.patient_id, r.source_split, s, g,
                        "" if row["dhash"] is None else f"{row['dhash']:064x}"])

    train_p, train_y, _ = subset(records, cache_rows, split, "train")
    val_p, val_y, val_idx = subset(records, cache_rows, split, "val")
    eval_sets = [s for s in ("test_internal", "test_ddr", "test_idrid", "test_messidor2") if s in split_names]
    log(f"train {len(train_p)} {np.bincount(train_y, minlength=5).tolist()} | val {len(val_p)} "
        f"{np.bincount(val_y, minlength=5).tolist()}")

    # 4. train + compare
    import tensorflow as tf
    log(f"TensorFlow {tf.__version__}; GPUs: {tf.config.list_physical_devices('GPU')}")
    from . import tf_model as M
    chosen = [dict(e) for e in EXPERIMENTS if args.experiments == "all" or e["name"] in args.experiments.split(",")]
    if args.epochs_override:
        for e in chosen:
            e["epochs"] = args.epochs_override
    runs = []
    budget = args.train_budget_hours * 3600
    for exp in chosen:
        elapsed = time.time() - t_start
        if runs and elapsed + runs[-1]["train_seconds"] * 1.3 > budget:
            log(f"skipping {exp['name']}: time budget ({elapsed / 3600:.1f} h used)")
            continue
        info = M.train(exp, (train_p, train_y), (val_p, val_y), os.path.join(args.out, "runs"), log, seed=SEED)
        runs.append(info)

    comparison = []
    test_subsets = {s: subset(records, cache_rows, split, s) for s in eval_sets}
    for info in runs:
        all_logits = M.predict_many(info, info["best_weights"], [val_p] + [test_subsets[s][0] for s in eval_sets])
        val_logits = all_logits[0]
        T, calib = choose_temperature(val_logits, val_y)
        p_val = softmax(val_logits, T)
        ref_thr = threshold_for_sensitivity(val_y >= REFERABLE_MIN_GRADE, referable_score(p_val), TARGET_REFERABLE_SENSITIVITY)
        urg_thr = threshold_for_sensitivity(val_y >= 3, p_val[:, 3:].sum(1), TARGET_URGENT_SENSITIVITY)
        res = {"name": info["name"], "arch": info["arch"], "loss": info["loss"], "temperature": T,
               "calibration_check": calib,
               "referable_threshold": ref_thr, "urgent_threshold": urg_thr,
               "train_hours": info["train_seconds"] / 3600,
               "val_uncalibrated": full_report(softmax(val_logits), val_y, ref_thr),
               "val": full_report(p_val, val_y, ref_thr), "tests": {}}
        np.savez_compressed(os.path.join(args.out, "runs", info["name"], "val_logits.npz"), logits=val_logits, y=val_y)
        for s, lg in zip(eval_sets, all_logits[1:]):
            p, y, idxs = test_subsets[s]
            np.savez_compressed(os.path.join(args.out, "runs", info["name"], f"{s}_logits.npz"), logits=lg, y=y)
            res["tests"][s] = full_report(softmax(lg, T), y, ref_thr)
            # The app only grades images its quality gate accepts; report that subset too.
            accepted = np.array([gate[i] == Q.ACCEPTED for i in idxs], dtype=bool)
            if accepted.any():
                res["tests"][s + "_gate_accepted"] = full_report(softmax(lg[accepted], T), y[accepted], ref_thr)
        comparison.append(res)
        log(f"{info['name']}: val QWK {res['val']['qwk']:.4f}, val acc {res['val']['accuracy']:.4f}, "
            f"ECE {res['val_uncalibrated']['ece']:.4f}->{res['val']['ece']:.4f}, T={T:.3f}")
    dump(comparison, os.path.join(reports, "model_comparison.json"))

    best = max(comparison, key=lambda r: r["val"]["qwk"])
    info = next(r for r in runs if r["name"] == best["name"])
    log(f"selected {best['name']} by validation QWK (test sets were not used for selection)")

    # 5. export + quantisation comparison
    export_dir = os.path.join(args.out, "export")
    os.makedirs(export_dir, exist_ok=True)
    rng = np.random.default_rng(SEED)
    rep = [train_p[i] for i in rng.choice(len(train_p), size=min(200, len(train_p)), replace=False)]
    exported = M.export_tflite(info, info["best_weights"], export_dir, rep, log)

    qsel = rng.choice(len(val_p), size=min(1000, len(val_p)), replace=False)
    q_paths = [val_p[i] for i in qsel]
    q_y = val_y[qsel]
    mess_p, mess_y, _ = subset(records, cache_rows, split, "test_messidor2") if "test_messidor2" in eval_sets else ([], np.array([]), [])
    from PIL import Image
    quant = {}
    ref_probs = None
    for name, path in exported["variants"].items():
        runner = M.TfliteRunner(path)
        probs, times = [], []
        for p in q_paths:
            img = np.asarray(Image.open(p).convert("RGB"))
            t0 = time.perf_counter()
            lg, _ = runner(img)
            times.append(time.perf_counter() - t0)
            probs.append(softmax(lg[None], best["temperature"])[0])
        probs = np.array(probs)
        if name == "fp32":
            ref_probs = probs
        rep_val = full_report(probs, q_y, best["referable_threshold"], n_boot=200)
        entry = {"size_mb": os.path.getsize(path) / 1e6, "sha256": sha256(path),
                 "val_subset_n": len(q_y), "val_subset_qwk": rep_val["qwk"],
                 "val_subset_referable_sensitivity": rep_val["referable"]["sensitivity"],
                 "val_subset_referable_specificity": rep_val["referable"]["specificity"],
                 "x86_cpu_latency_ms_mean": 1000 * float(np.mean(times[5:] or times)),
                 "note": "latency measured on the Kaggle x86 CPU with 4 threads, NOT on an Android phone"}
        if ref_probs is not None:
            entry["argmax_agreement_vs_fp32"] = float((probs.argmax(1) == ref_probs.argmax(1)).mean())
            entry["max_abs_prob_diff_vs_fp32"] = float(np.abs(probs - ref_probs).max())
        if len(mess_p):
            mp = np.array([softmax(runner(np.asarray(Image.open(p).convert("RGB")))[0][None], best["temperature"])[0] for p in mess_p])
            entry["messidor2_qwk"] = full_report(mp, mess_y, best["referable_threshold"], n_boot=100)["qwk"]
        quant[name] = entry
        log(f"  {name}: {entry['size_mb']:.1f} MB, val-subset QWK {entry['val_subset_qwk']:.4f}, "
            f"agree {entry.get('argmax_agreement_vs_fp32', 1):.4f}, {entry['x86_cpu_latency_ms_mean']:.0f} ms")
    fp32 = quant["fp32"]
    ok = [n for n, e in quant.items()
          if e["val_subset_qwk"] >= fp32["val_subset_qwk"] - 0.01
          and (e["val_subset_referable_sensitivity"] or 0) >= (fp32["val_subset_referable_sensitivity"] or 0) - 0.01
          and e.get("argmax_agreement_vs_fp32", 1.0) >= 0.98]
    deploy = min(ok, key=lambda n: quant[n]["size_mb"])
    log(f"deployment variant: {deploy} (smallest within 0.01 QWK / referable sensitivity of FP32, >=98% agreement)")
    dump({"variants": quant, "selected": deploy,
          "rule": "smallest variant with val-subset QWK and referable sensitivity within 0.01 of FP32 "
                  "and >= 98% argmax agreement with FP32"},
         os.path.join(reports, "quantization.json"))

    # 6. explanation check: CAM from the exported model vs textbook Grad-CAM
    deploy_runner = M.TfliteRunner(exported["variants"][deploy])
    kernel = exported["cam_kernel"]
    imgs = [np.asarray(Image.open(val_p[i]).convert("RGB")) for i in qsel[:12]]
    outs = [deploy_runner(im) for im in imgs]
    classes = [int(o[0].argmax()) for o in outs]
    gradcams = M.gradcam_keras(info, info["best_weights"], imgs, classes)
    corrs = []
    for (lg, feats), c, gc in zip(outs, classes, gradcams):
        cam = M.cam_from_features(feats, kernel, c)
        if cam.std() > 0 and gc.std() > 0:
            corrs.append(float(np.corrcoef(cam.ravel(), gc.ravel())[0, 1]))
    log(f"CAM vs Grad-CAM Pearson r over {len(corrs)} images: mean {np.mean(corrs):.4f}, min {np.min(corrs):.4f}")
    dump({"pearson_r": corrs, "mean": float(np.mean(corrs)), "min": float(np.min(corrs)),
          "explanation": "CAM computed from the deployed LiteRT model's feature output and head weights, "
                         "compared with GradientTape Grad-CAM on the Keras FP32 model."},
         os.path.join(reports, "explainability_check.json"))

    # 7. Android assets
    model_file = "netra_dr.tflite"
    shutil.copy(exported["variants"][deploy], os.path.join(assets, model_file))
    cam_bin = os.path.join(assets, "cam_weights.bin")
    np.ascontiguousarray(kernel.astype("<f4")).tofile(cam_bin)
    feat_shape = list(outs[0][1].shape)
    version = f"{best['name']}-{deploy}-{time.strftime('%Y%m%d')}"
    test_summary = {s: {"n": r["n"], "qwk": r["qwk"], "accuracy": r["accuracy"],
                        "referable_sensitivity": r["referable"]["sensitivity"],
                        "referable_specificity": r["referable"]["specificity"]}
                    for s, r in best["tests"].items()}
    config = {
        "schema": 1,
        "model_version": version,
        "architecture": best["arch"],
        "training_loss": best["loss"],
        "quantization": deploy,
        "files": {"model": {"name": model_file, "sha256": sha256(os.path.join(assets, model_file))},
                  "cam_weights": {"name": "cam_weights.bin", "sha256": sha256(cam_bin),
                                  "shape": list(kernel.shape), "dtype": "float32-le", "layout": "row-major [channel][class]"}},
        "input": {"signature": "serving_default", "name": "image", "shape": [1, OUT_SIZE, OUT_SIZE, 3],
                  "dtype": "float32", "range": [0, 255], "channel_order": "RGB"},
        "outputs": {"logits": [1, 5], "features": [1] + feat_shape},
        "preprocessing": {"work_max_side": 1024, "mask_threshold": 20, "bbox_min_fraction": 0.02,
                          "out_size": OUT_SIZE, "reference": "ml/netra_ml/preprocess.py"},
        "classes": CLASS_NAMES,
        "calibration": {"method": "temperature_scaling", "temperature": best["temperature"], "fitted_on": "val"},
        "decision": {"referable_min_grade": REFERABLE_MIN_GRADE,
                     "referable_threshold": best["referable_threshold"],
                     "referable_target_sensitivity_on_val": TARGET_REFERABLE_SENSITIVITY,
                     "urgent_threshold": best["urgent_threshold"],
                     "urgent_target_sensitivity_on_val": TARGET_URGENT_SENSITIVITY,
                     "review_min_confidence": REVIEW_MIN_CONFIDENCE,
                     "status": "PROVISIONAL - not reviewed by an ophthalmologist"},
        "quality_thresholds": thresholds,
        "evaluation_summary": {"val": {"n": best["val"]["n"], "qwk": best["val"]["qwk"]}, **test_summary},
    }
    dump(config, os.path.join(assets, "model_config.json"))
    for name in ("netra_dr_fp32.tflite",):
        if name in os.listdir(export_dir):
            shutil.copy(os.path.join(export_dir, name), os.path.join(args.out, name))

    # 8. golden files for Android parity tests (model + preprocessing)
    golden_items = []
    for s in ("test_messidor2", "test_idrid", "val"):
        p, y, idxs = subset(records, cache_rows, split, s)
        if idxs:
            golden_items.append((s, idxs[0]))
    cfg_dec = config["decision"]
    for n, (s, i) in enumerate(golden_items):
        rgb = load_rgb(records[i].path)
        im = Image.fromarray(rgb)
        im.thumbnail((1000, 1000), Image.LANCZOS)
        in_path = os.path.join(golden, f"golden_{n}_input.png")
        im.save(in_path)
        rgb_small = np.asarray(Image.open(in_path).convert("RGB"))
        qres, pre = Q.assess(rgb_small, thresholds)
        Image.fromarray(pre.image).save(os.path.join(golden, f"golden_{n}_preprocessed.png"))
        lg, feats = deploy_runner(pre.image)
        probs = softmax(lg[None], best["temperature"])[0]
        d = decide(probs, cfg_dec)
        dump({"source_split": s, "label": records[i].label, "quality": qres.status,
              "quality_metrics": qres.metrics.as_dict(), "logits": lg, "probs": probs,
              "decision": d.__dict__, "geometry": pre.geometry.__dict__,
              "cam_predicted": M.cam_from_features(feats, kernel, int(probs.argmax()))},
             os.path.join(golden, f"golden_{n}.json"))

    # 9. tests inside the training environment
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    test_dir = os.path.join(here, "tests")
    if os.path.isdir(test_dir):
        res = subprocess.run([sys.executable, "-m", "pytest", "-q", test_dir, "-p", "no:cacheprovider"],
                             capture_output=True, text=True, cwd=here,
                             env={**os.environ, "NETRA_TFLITE": exported["variants"][deploy]})
        with open(os.path.join(reports, "pytest_kaggle.txt"), "w") as fh:
            fh.write(res.stdout + res.stderr)
        log("pytest: " + (res.stdout.strip().splitlines() or ["(no output)"])[-1])

    # Keep outputs small: drop backups and per-run weights except the selected one.
    for r in runs:
        shutil.rmtree(os.path.join(args.out, "runs", r["name"], "backup"), ignore_errors=True)
        if r["name"] != best["name"]:
            try:
                os.remove(r["best_weights"])
            except OSError:
                pass
    shutil.rmtree(os.path.join(export_dir, "saved_model"), ignore_errors=True)
    log(f"done in {(time.time() - t_start) / 3600:.2f} h")


if __name__ == "__main__":
    main()
