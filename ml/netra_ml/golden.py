"""Generates golden files that pin the Kotlin port to the Python reference.

    python -m netra_ml.golden --out ../app/src/test/resources/golden

For each case it writes:
  <case>_input.rgb.gz      gzipped raw RGB input (decoded once here, so decoder differences can't interfere)
  <case>_expected.rgb.gz   gzipped raw OUT_SIZE*OUT_SIZE*3 bytes from preprocess()
  <case>.json              geometry, quality metrics and quality status (default thresholds)
"""

from __future__ import annotations

import argparse
import gzip
import json
import os

import numpy as np

from . import quality as Q
from .preprocess import load_rgb


def synthetic_fundus(h: int, w: int, seed: int = 0, brightness: float = 1.0, blur: bool = False) -> np.ndarray:
    """A crude fundus-like test image: black background, orange-red disc, vessels, noise."""
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:h, 0:w]
    cy, cx, r = h / 2, w / 2, 0.45 * min(h, w)
    d = np.sqrt((yy - cy) ** 2 + (xx - cx) ** 2)
    disc = d < r
    img = np.zeros((h, w, 3), np.float64)
    shade = 1.0 - 0.5 * (d / r) ** 2
    img[..., 0] = 200 * shade
    img[..., 1] = 90 * shade
    img[..., 2] = 40 * shade
    if not blur:
        for k in range(12):
            ang = rng.uniform(0, 2 * np.pi)
            line = np.abs((yy - cy) * np.cos(ang) - (xx - cx) * np.sin(ang)) < 1.5 + k % 3
            img[line & disc] *= 0.55
        img += rng.normal(0, 6, img.shape)
    img *= brightness
    img[~disc] = 0
    return np.clip(img, 0, 255).astype(np.uint8)


def cases(repo_root: str):
    sample = os.path.join(repo_root, "tools", "sample_fundus.jpg")
    if os.path.isfile(sample):
        yield "sample_fundus", load_rgb(sample)
    yield "synthetic_landscape", synthetic_fundus(600, 900, seed=1)
    yield "synthetic_odd_size", synthetic_fundus(1101, 1037, seed=2)
    yield "synthetic_dark", synthetic_fundus(700, 700, seed=3, brightness=0.15)
    flat = np.full((640, 480, 3), 128, np.uint8)
    yield "flat_grey", flat


def _write_gz(path: str, arr: np.ndarray) -> None:
    # mtime=0 keeps the file byte-identical between runs (no git churn).
    with open(path, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0) as fh:
        fh.write(np.ascontiguousarray(arr, dtype=np.uint8).tobytes())


def read_gz(path: str) -> np.ndarray:
    with gzip.open(path, "rb") as fh:
        return np.frombuffer(fh.read(), np.uint8)


def write(out_dir: str, repo_root: str) -> list[str]:
    os.makedirs(out_dir, exist_ok=True)
    names = []
    for name, rgb in cases(repo_root):
        # Raw pixels: Android JVM unit tests have no image decoder.
        _write_gz(os.path.join(out_dir, f"{name}_input.rgb.gz"), rgb)
        result, pre = Q.assess(rgb)
        meta = {"case": name, "width": int(rgb.shape[1]), "height": int(rgb.shape[0]),
                "status": result.status, "reasons": list(result.reasons),
                "metrics": result.metrics.as_dict() if result.metrics else None,
                "geometry": pre.geometry.__dict__ if pre else None}
        if pre is not None:
            _write_gz(os.path.join(out_dir, f"{name}_expected.rgb.gz"), pre.image)
        with open(os.path.join(out_dir, f"{name}.json"), "w") as fh:
            json.dump(meta, fh, indent=2)
        names.append(name)
    with open(os.path.join(out_dir, "cases.txt"), "w") as fh:
        fh.write("\n".join(names) + "\n")
    return names


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--repo-root", default=os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))
    a = ap.parse_args()
    print(write(a.out, a.repo_root))
