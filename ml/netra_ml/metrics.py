"""Evaluation metrics for 5-class DR grading and binary referable-DR screening.

Pure NumPy so the tests run on any laptop. scikit-learn is used only for the
ROC/PR AUC helpers when it is installed.
"""

from __future__ import annotations

import numpy as np

NUM_CLASSES = 5
CLASS_NAMES = ["No DR", "Mild", "Moderate", "Severe", "Proliferative"]


def softmax(logits: np.ndarray, temperature: float = 1.0) -> np.ndarray:
    z = np.asarray(logits, dtype=np.float64) / temperature
    z = z - z.max(axis=1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)


def confusion_matrix(y_true: np.ndarray, y_pred: np.ndarray, k: int = NUM_CLASSES) -> np.ndarray:
    cm = np.zeros((k, k), dtype=np.int64)
    np.add.at(cm, (np.asarray(y_true), np.asarray(y_pred)), 1)
    return cm


def quadratic_weighted_kappa(y_true: np.ndarray, y_pred: np.ndarray, k: int = NUM_CLASSES) -> float:
    cm = confusion_matrix(y_true, y_pred, k).astype(np.float64)
    n = cm.sum()
    if n == 0:
        return float("nan")
    i, j = np.indices((k, k))
    w = (i - j) ** 2 / (k - 1) ** 2
    expected = np.outer(cm.sum(axis=1), cm.sum(axis=0)) / n
    denom = (w * expected).sum()
    if denom == 0:
        return 1.0
    return float(1.0 - (w * cm).sum() / denom)


def per_class_stats(cm: np.ndarray) -> list[dict]:
    """One-vs-rest sensitivity (recall), specificity, precision (PPV), F1 per class."""
    total = cm.sum()
    out = []
    for c in range(cm.shape[0]):
        tp = cm[c, c]
        fn = cm[c].sum() - tp
        fp = cm[:, c].sum() - tp
        tn = total - tp - fn - fp
        sens = _div(tp, tp + fn)
        spec = _div(tn, tn + fp)
        prec = _div(tp, tp + fp)
        f1 = _div(2 * prec * sens, prec + sens) if not (np.isnan(prec) or np.isnan(sens)) else float("nan")
        out.append({"class": c, "name": CLASS_NAMES[c], "support": int(tp + fn),
                    "sensitivity": sens, "specificity": spec, "precision": prec, "f1": f1})
    return out


def binary_stats(y_true: np.ndarray, y_pred: np.ndarray) -> dict:
    y_true = np.asarray(y_true).astype(bool)
    y_pred = np.asarray(y_pred).astype(bool)
    tp = int((y_true & y_pred).sum())
    tn = int((~y_true & ~y_pred).sum())
    fp = int((~y_true & y_pred).sum())
    fn = int((y_true & ~y_pred).sum())
    return {"tp": tp, "tn": tn, "fp": fp, "fn": fn,
            "sensitivity": _div(tp, tp + fn), "specificity": _div(tn, tn + fp),
            "ppv": _div(tp, tp + fp), "npv": _div(tn, tn + fn),
            "prevalence": _div(tp + fn, tp + tn + fp + fn)}


def referable_score(probs: np.ndarray, min_grade: int = 2) -> np.ndarray:
    """Model score for referable DR = summed probability of grades >= min_grade."""
    return np.asarray(probs)[:, min_grade:].sum(axis=1)


def brier_multiclass(probs: np.ndarray, y_true: np.ndarray) -> float:
    onehot = np.eye(probs.shape[1])[np.asarray(y_true)]
    return float(((probs - onehot) ** 2).sum(axis=1).mean())


def expected_calibration_error(probs: np.ndarray, y_true: np.ndarray, bins: int = 15) -> float:
    """Top-label ECE with equal-width confidence bins."""
    conf = probs.max(axis=1)
    pred = probs.argmax(axis=1)
    correct = (pred == np.asarray(y_true)).astype(np.float64)
    edges = np.linspace(0.0, 1.0, bins + 1)
    ece = 0.0
    n = len(conf)
    for lo, hi in zip(edges[:-1], edges[1:]):
        sel = (conf > lo) & (conf <= hi)
        if sel.any():
            ece += sel.sum() / n * abs(correct[sel].mean() - conf[sel].mean())
    return float(ece)


def auc_scores(y_true_bin: np.ndarray, score: np.ndarray) -> dict:
    try:
        from sklearn.metrics import average_precision_score, roc_auc_score
    except ImportError:  # pragma: no cover
        return {"roc_auc": float("nan"), "pr_auc": float("nan")}
    y = np.asarray(y_true_bin).astype(int)
    if y.min() == y.max():
        return {"roc_auc": float("nan"), "pr_auc": float("nan")}
    return {"roc_auc": float(roc_auc_score(y, score)), "pr_auc": float(average_precision_score(y, score))}


def bootstrap_ci(fn, *arrays, n_boot: int = 1000, seed: int = 0, alpha: float = 0.05) -> tuple[float, float]:
    """Percentile bootstrap CI of fn(*arrays) resampling rows."""
    rng = np.random.default_rng(seed)
    n = len(arrays[0])
    vals = []
    for _ in range(n_boot):
        idx = rng.integers(0, n, n)
        v = fn(*[np.asarray(a)[idx] for a in arrays])
        if not np.isnan(v):
            vals.append(v)
    if not vals:
        return float("nan"), float("nan")
    return float(np.percentile(vals, 100 * alpha / 2)), float(np.percentile(vals, 100 * (1 - alpha / 2)))


def full_report(probs: np.ndarray, y_true: np.ndarray, referable_threshold: float,
                min_referable_grade: int = 2, n_boot: int = 500) -> dict:
    y_true = np.asarray(y_true)
    pred = probs.argmax(axis=1)
    cm = confusion_matrix(y_true, pred)
    stats = per_class_stats(cm)
    ref_true = y_true >= min_referable_grade
    ref_score = referable_score(probs, min_referable_grade)
    ref_pred = ref_score >= referable_threshold
    binary = binary_stats(ref_true, ref_pred)

    def sens(t, p):
        return binary_stats(t, p)["sensitivity"]

    def spec(t, p):
        return binary_stats(t, p)["specificity"]

    severe_as_no_dr = int(((y_true >= 3) & (pred == 0)).sum())
    report = {
        "n": int(len(y_true)),
        "class_counts": np.bincount(y_true, minlength=NUM_CLASSES).tolist(),
        "accuracy": float((pred == y_true).mean()),
        "qwk": quadratic_weighted_kappa(y_true, pred),
        "qwk_ci95": bootstrap_ci(quadratic_weighted_kappa, y_true, pred, n_boot=n_boot),
        "macro_precision": _nanmean([s["precision"] for s in stats]),
        "macro_recall": _nanmean([s["sensitivity"] for s in stats]),
        "macro_f1": _nanmean([s["f1"] for s in stats]),
        "per_class": stats,
        "confusion_matrix": cm.tolist(),
        "referable": {
            "definition": f"grade >= {min_referable_grade}",
            "threshold": referable_threshold,
            **binary,
            "sensitivity_ci95": bootstrap_ci(sens, ref_true, ref_pred, n_boot=n_boot),
            "specificity_ci95": bootstrap_ci(spec, ref_true, ref_pred, n_boot=n_boot),
            **auc_scores(ref_true, ref_score),
        },
        "severe_or_pdr_predicted_no_dr": severe_as_no_dr,
        "brier": brier_multiclass(probs, y_true),
        "ece": expected_calibration_error(probs, y_true),
    }
    return report


def threshold_for_sensitivity(y_true_bin: np.ndarray, score: np.ndarray, target: float) -> float:
    """Highest threshold whose sensitivity on (y_true_bin, score) is >= target."""
    pos = np.sort(np.asarray(score)[np.asarray(y_true_bin).astype(bool)])
    if pos.size == 0:
        return 0.5
    k = int(np.floor((1.0 - target) * pos.size))
    return float(pos[min(k, pos.size - 1)])


def _div(a, b) -> float:
    return float(a) / float(b) if b else float("nan")


def _nanmean(values) -> float:
    arr = np.array(values, dtype=np.float64)
    return float(np.nanmean(arr)) if np.any(~np.isnan(arr)) else float("nan")
