"""Deterministic fundus preprocessing - the reference implementation.

Every step uses integer arithmetic or float64 bilinear sampling with an explicit
rounding rule, so that the Kotlin port (RetinaPreprocessor.kt) can reproduce the
output bit-for-bit (golden tests allow a difference of at most 1 grey level on a
tiny fraction of pixels, for float rounding ties).

Pipeline (input: RGB uint8 array, EXIF orientation already applied):

  A. Working copy   : integer box-average by f = ceil(max(H, W) / WORK_MAX_SIDE)
  B. Retina mask    : pixel is "retina" when R + G + B > 3 * MASK_THRESHOLD
                      bounding box = rows/cols whose mask count >= 2 % of the
                      opposite dimension
  C. Square crop    : crop to the bounding box, centre it on a black square
  D. Resize         : integer box-average by g = floor(side / OUT_SIZE) (if >= 2),
                      then bilinear (half-pixel centres) to OUT_SIZE x OUT_SIZE,
                      rounded with floor(v + 0.5)

The model receives the OUT_SIZE x OUT_SIZE RGB image as float32 in [0, 255];
EfficientNetV2's own Rescaling/Normalization layers live inside the model.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

WORK_MAX_SIDE = 1024
MASK_THRESHOLD = 20
BBOX_MIN_FRACTION = 0.02
OUT_SIZE = 384


class NoRetinaFoundError(ValueError):
    """Raised when no plausible retinal disc can be located in the image."""


@dataclass(frozen=True)
class Geometry:
    """How the model input maps back onto the working image (for heatmaps)."""

    work_height: int
    work_width: int
    top: int        # bounding box in working-image pixels (inclusive start)
    left: int
    bbox_height: int
    bbox_width: int
    side: int       # square side before the final resize
    pad_top: int    # where the crop sits inside the square
    pad_left: int


@dataclass(frozen=True)
class Preprocessed:
    image: np.ndarray          # OUT_SIZE x OUT_SIZE x 3 uint8
    work: np.ndarray           # working image, uint8
    mask: np.ndarray           # working-image retina mask, bool
    geometry: Geometry


def box_downsample(img: np.ndarray, factor: int) -> np.ndarray:
    """Integer box average over factor x factor blocks; remainder rows/cols dropped.

    Rounds half up: (sum + n // 2) // n, all in integers.
    """
    if factor <= 1:
        return img.copy()
    h = (img.shape[0] // factor) * factor
    w = (img.shape[1] // factor) * factor
    if h == 0 or w == 0:
        raise ValueError("image smaller than the downsample factor")
    blocks = img[:h, :w].astype(np.int64)
    blocks = blocks.reshape(h // factor, factor, w // factor, factor, -1)
    n = factor * factor
    summed = blocks.sum(axis=(1, 3))
    return ((summed + n // 2) // n).astype(np.uint8)


def working_copy(rgb: np.ndarray) -> np.ndarray:
    _check_rgb(rgb)
    longest = max(rgb.shape[0], rgb.shape[1])
    factor = max(1, -(-longest // WORK_MAX_SIDE))  # ceil division
    return box_downsample(rgb, factor)


def retina_mask(work: np.ndarray) -> np.ndarray:
    total = work.astype(np.int32).sum(axis=2)
    return total > 3 * MASK_THRESHOLD


def retina_bbox(mask: np.ndarray) -> tuple[int, int, int, int]:
    """Returns (top, left, height, width) of the retina, or raises NoRetinaFoundError."""
    h, w = mask.shape
    row_counts = mask.sum(axis=1)
    col_counts = mask.sum(axis=0)
    rows = np.nonzero(row_counts >= max(1, int(BBOX_MIN_FRACTION * w)))[0]
    cols = np.nonzero(col_counts >= max(1, int(BBOX_MIN_FRACTION * h)))[0]
    if rows.size == 0 or cols.size == 0:
        raise NoRetinaFoundError("no bright region found")
    top, bottom = int(rows[0]), int(rows[-1])
    left, right = int(cols[0]), int(cols[-1])
    bh, bw = bottom - top + 1, right - left + 1
    if bh * bw < 0.05 * h * w or min(bh, bw) < 32:
        raise NoRetinaFoundError("bright region too small")
    return top, left, bh, bw


def bilinear_resize(img: np.ndarray, out_h: int, out_w: int) -> np.ndarray:
    """Bilinear resize with half-pixel centres and edge clamping, float64, round half up."""
    in_h, in_w = img.shape[:2]
    sy = in_h / out_h
    sx = in_w / out_w
    ys = (np.arange(out_h, dtype=np.float64) + 0.5) * sy - 0.5
    xs = (np.arange(out_w, dtype=np.float64) + 0.5) * sx - 0.5
    ys = np.clip(ys, 0.0, in_h - 1)
    xs = np.clip(xs, 0.0, in_w - 1)
    y0 = np.floor(ys).astype(np.int64)
    x0 = np.floor(xs).astype(np.int64)
    y1 = np.minimum(y0 + 1, in_h - 1)
    x1 = np.minimum(x0 + 1, in_w - 1)
    wy = (ys - y0)[:, None, None]
    wx = (xs - x0)[None, :, None]
    src = img.astype(np.float64)
    top = src[y0][:, x0] * (1.0 - wx) + src[y0][:, x1] * wx
    bottom = src[y1][:, x0] * (1.0 - wx) + src[y1][:, x1] * wx
    out = top * (1.0 - wy) + bottom * wy
    return np.floor(out + 0.5).clip(0, 255).astype(np.uint8)


def preprocess(rgb: np.ndarray, out_size: int = OUT_SIZE) -> Preprocessed:
    work = working_copy(rgb)
    mask = retina_mask(work)
    top, left, bh, bw = retina_bbox(mask)

    side = max(bh, bw)
    pad_top = (side - bh) // 2
    pad_left = (side - bw) // 2
    square = np.zeros((side, side, 3), dtype=np.uint8)
    square[pad_top:pad_top + bh, pad_left:pad_left + bw] = work[top:top + bh, left:left + bw]

    g = side // out_size
    if g >= 2:
        square = box_downsample(square, g)
    image = bilinear_resize(square, out_size, out_size)

    geometry = Geometry(
        work_height=work.shape[0], work_width=work.shape[1],
        top=top, left=left, bbox_height=bh, bbox_width=bw,
        side=side, pad_top=pad_top, pad_left=pad_left,
    )
    return Preprocessed(image=image, work=work, mask=mask, geometry=geometry)


def load_rgb(path: str) -> np.ndarray:
    """Decodes an image file to RGB uint8, applying EXIF orientation."""
    from PIL import Image, ImageOps

    with Image.open(path) as im:
        im = ImageOps.exif_transpose(im)
        return np.asarray(im.convert("RGB"), dtype=np.uint8)


def _check_rgb(rgb: np.ndarray) -> None:
    if rgb.dtype != np.uint8 or rgb.ndim != 3 or rgb.shape[2] != 3:
        raise ValueError(f"expected HxWx3 uint8 RGB, got {rgb.dtype} {rgb.shape}")
