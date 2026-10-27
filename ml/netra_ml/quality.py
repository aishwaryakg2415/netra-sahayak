"""Image-quality gate - the reference implementation (ported to ImageQualityAssessor.kt).

All measurements are simple, deterministic statistics. They catch obvious
problems (not a fundus photo, too dark, too bright, blurred, too small); they do
NOT prove that an image is gradable. Thresholds are fitted on training data by
`fit_thresholds` and shipped in model_config.json so Python and Android agree.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass

import numpy as np

from .preprocess import MASK_THRESHOLD, NoRetinaFoundError, Preprocessed, preprocess, working_copy

# Mean channel value below which an image without a detectable disc counts as too dark.
DARK_IMAGE_MEAN = 30

ACCEPTED = "ACCEPTED_FOR_SCREENING"
RETAKE = "RETAKE_IMAGE"
UNSUPPORTED = "UNSUPPORTED_IMAGE"
REVIEW = "REVIEW_REQUIRED"

# Provisional defaults; replaced by thresholds fitted on training data.
DEFAULT_THRESHOLDS = {
    "min_side_px": 256,
    "retina_fraction_min": 0.20,
    "corner_dark_min": 0.50,
    "red_ratio_min": 1.10,
    "brightness_min": 25.0,
    "brightness_max": 200.0,
    "saturated_max": 0.10,
    "sharpness_min": 2.0,
}


@dataclass(frozen=True)
class QualityMetrics:
    min_side_px: int
    retina_fraction: float
    corner_dark_fraction: float
    red_ratio: float
    mean_brightness: float
    saturated_fraction: float
    sharpness: float

    def as_dict(self) -> dict:
        return asdict(self)


@dataclass(frozen=True)
class QualityResult:
    status: str
    reasons: tuple[str, ...]
    metrics: QualityMetrics | None


def corner_dark_fraction(mask: np.ndarray) -> float:
    h, w = mask.shape
    ch = max(1, -(-h // 10))
    cw = max(1, -(-w // 10))
    corners = [mask[:ch, :cw], mask[:ch, w - cw:], mask[h - ch:, :cw], mask[h - ch:, w - cw:]]
    dark = sum(int((~c).sum()) for c in corners)
    total = sum(c.size for c in corners)
    return dark / total


def central_disk(n: int) -> np.ndarray:
    """Pixels whose centre lies within 0.42 * n of the image centre (integer test)."""
    idx = np.arange(n, dtype=np.int64)
    # Compare in doubled coordinates to keep everything integer:
    # (2y + 1 - n)^2 + (2x + 1 - n)^2 <= (0.84 n)^2  ->  scaled by 100 to stay integer.
    dy = (2 * idx + 1 - n)[:, None]
    dx = (2 * idx + 1 - n)[None, :]
    return (dy * dy + dx * dx) * 2500 <= (42 * n) ** 2


def laplacian_variance(green: np.ndarray, region: np.ndarray) -> float:
    g = green.astype(np.int64)
    lap = np.zeros_like(g)
    lap[1:-1, 1:-1] = (g[:-2, 1:-1] + g[2:, 1:-1] + g[1:-1, :-2] + g[1:-1, 2:]
                       - 4 * g[1:-1, 1:-1])
    inner = np.zeros_like(region)
    inner[1:-1, 1:-1] = region[1:-1, 1:-1]
    vals = lap[inner]
    if vals.size == 0:
        return 0.0
    n = vals.size
    s = int(vals.sum())
    s2 = int((vals * vals).sum())
    return (s2 / n) - (s / n) ** 2


def measure(pre: Preprocessed, original_min_side: int) -> QualityMetrics:
    img = pre.image
    n = img.shape[0]
    total = img.astype(np.int32).sum(axis=2)
    out_mask = total > 3 * MASK_THRESHOLD
    count = int(out_mask.sum())
    if count == 0:
        red_ratio = 0.0
        brightness = 0.0
        saturated = 0.0
    else:
        r = int(img[..., 0][out_mask].astype(np.int64).sum())
        g = int(img[..., 1][out_mask].astype(np.int64).sum())
        red_ratio = r / max(g, 1)
        brightness = int(total[out_mask].astype(np.int64).sum()) / (3 * count)
        saturated = int((img.max(axis=2)[out_mask] >= 250).sum()) / count
    region = central_disk(n) & out_mask
    sharp = laplacian_variance(img[..., 1], region)
    return QualityMetrics(
        min_side_px=int(original_min_side),
        retina_fraction=float(pre.mask.mean()),
        corner_dark_fraction=float(corner_dark_fraction(pre.mask)),
        red_ratio=float(red_ratio),
        mean_brightness=float(brightness),
        saturated_fraction=float(saturated),
        sharpness=float(sharp),
    )


def decide(m: QualityMetrics, t: dict) -> QualityResult:
    unsupported: list[str] = []
    retake: list[str] = []
    if m.retina_fraction < t["retina_fraction_min"]:
        unsupported.append("retina_not_found")
    if m.corner_dark_fraction < t["corner_dark_min"]:
        unsupported.append("not_fundus_layout")
    if m.red_ratio < t["red_ratio_min"]:
        unsupported.append("not_fundus_colour")
    if m.min_side_px < t["min_side_px"]:
        retake.append("too_small")
    if m.mean_brightness < t["brightness_min"]:
        retake.append("too_dark")
    if m.mean_brightness > t["brightness_max"]:
        retake.append("too_bright")
    if m.saturated_fraction > t["saturated_max"]:
        retake.append("glare")
    if m.sharpness < t["sharpness_min"]:
        retake.append("blurred")
    if unsupported and "too_dark" not in retake:
        return QualityResult(UNSUPPORTED, tuple(unsupported + retake), m)
    if retake or unsupported:
        return QualityResult(RETAKE, tuple(retake + unsupported), m)
    return QualityResult(ACCEPTED, (), m)


def assess(rgb: np.ndarray, thresholds: dict | None = None) -> tuple[QualityResult, Preprocessed | None]:
    t = thresholds or DEFAULT_THRESHOLDS
    try:
        pre = preprocess(rgb)
    except NoRetinaFoundError:
        # A badly under-exposed photo has no detectable disc either; asking for a
        # retake is the more useful message than "not a fundus image".
        work = working_copy(rgb)
        total = int(work.astype(np.int64).sum())
        if total < DARK_IMAGE_MEAN * 3 * work.shape[0] * work.shape[1]:
            return QualityResult(RETAKE, ("too_dark", "retina_not_found"), None), None
        return QualityResult(UNSUPPORTED, ("retina_not_found",), None), None
    m = measure(pre, min(rgb.shape[0], rgb.shape[1]))
    return decide(m, t), pre


def fit_thresholds(metrics: list[QualityMetrics], low_pct: float = 0.5, high_pct: float = 99.5) -> dict:
    """Fits thresholds so that ~low_pct % of curated, gradable training images fall
    outside each limit. This bounds the false-rejection rate on curated data only;
    it says nothing about sensitivity to genuinely ungradable images, which must be
    measured separately (e.g. on DDR's ungradable class)."""
    arr = {k: np.array([getattr(m, k) for m in metrics], dtype=np.float64)
           for k in QualityMetrics.__dataclass_fields__}
    return {
        "min_side_px": DEFAULT_THRESHOLDS["min_side_px"],
        "retina_fraction_min": float(np.percentile(arr["retina_fraction"], low_pct)),
        "corner_dark_min": float(np.percentile(arr["corner_dark_fraction"], low_pct)),
        "red_ratio_min": float(np.percentile(arr["red_ratio"], low_pct)),
        "brightness_min": float(np.percentile(arr["mean_brightness"], low_pct)),
        "brightness_max": float(np.percentile(arr["mean_brightness"], high_pct)),
        "saturated_max": float(np.percentile(arr["saturated_fraction"], high_pct)),
        "sharpness_min": float(np.percentile(arr["sharpness"], low_pct)),
    }
