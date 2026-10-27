"""Copies a finished training run into the Android project, verifying checksums.

    python ml/install_model.py <pipeline-output-dir>

<pipeline-output-dir> is the `out/` folder produced by netra_ml.pipeline (for a
Kaggle run: `kaggle kernels output <user>/netra-dr-train -p <dir>`, then <dir>/out).

Installs:
  out/android_assets/*  -> app/src/main/assets/model/
  out/golden/*          -> app/src/androidTest/assets/   (on-device parity tests)
  out/reports/*.json    -> ml/results/                   (committed evidence)
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main(out_dir: str) -> None:
    assets = os.path.join(out_dir, "android_assets")
    cfg = json.load(open(os.path.join(assets, "model_config.json")))
    for key in ("model", "cam_weights"):
        ref = cfg["files"][key]
        actual = sha256(os.path.join(assets, ref["name"]))
        if actual != ref["sha256"]:
            sys.exit(f"checksum mismatch for {ref['name']}: refusing to install")

    dst = os.path.join(REPO, "app", "src", "main", "assets", "model")
    os.makedirs(dst, exist_ok=True)
    for name in ("model_config.json", cfg["files"]["model"]["name"], cfg["files"]["cam_weights"]["name"]):
        shutil.copy(os.path.join(assets, name), os.path.join(dst, name))

    gold_src = os.path.join(out_dir, "golden")
    gold_dst = os.path.join(REPO, "app", "src", "androidTest", "assets")
    if os.path.isdir(gold_src):
        os.makedirs(gold_dst, exist_ok=True)
        for f in os.listdir(gold_src):
            shutil.copy(os.path.join(gold_src, f), os.path.join(gold_dst, f))

    res = os.path.join(REPO, "ml", "results")
    os.makedirs(res, exist_ok=True)
    for f in os.listdir(os.path.join(out_dir, "reports")):
        if f.endswith((".json", ".txt", ".log")):
            shutil.copy(os.path.join(out_dir, "reports", f), os.path.join(res, f))
    print(f"installed model {cfg['model_version']} ({cfg['quantization']}) into {dst}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
