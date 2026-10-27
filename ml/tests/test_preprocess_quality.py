import os

import numpy as np
import pytest
from PIL import Image

from netra_ml import quality as Q
from netra_ml.golden import synthetic_fundus
from netra_ml.preprocess import (OUT_SIZE, NoRetinaFoundError, bilinear_resize, box_downsample,
                                 load_rgb, preprocess)


def test_box_downsample_is_exact_integer_mean():
    img = np.arange(4 * 4 * 3, dtype=np.uint8).reshape(4, 4, 3)
    out = box_downsample(img, 2)
    expected = (img[:2, :2].astype(int).sum((0, 1)) + 2) // 4
    assert out.shape == (2, 2, 3)
    assert np.array_equal(out[0, 0], expected)


def test_bilinear_same_size_is_identity():
    img = np.random.default_rng(0).integers(0, 256, (17, 23, 3), dtype=np.uint8)
    assert np.array_equal(bilinear_resize(img, 17, 23), img)


def test_preprocess_output_shape_and_determinism():
    rgb = synthetic_fundus(900, 1300, seed=4)
    a = preprocess(rgb)
    b = preprocess(rgb)
    assert a.image.shape == (OUT_SIZE, OUT_SIZE, 3) and a.image.dtype == np.uint8
    assert np.array_equal(a.image, b.image)


def test_preprocess_crops_black_border_to_square_retina():
    rgb = synthetic_fundus(800, 1400, seed=5)
    pre = preprocess(rgb)
    g = pre.geometry
    # 1400 px > 1024, so the working copy is halved: disc diameter 0.9 * 800 / 2 = 360 px.
    assert (g.work_height, g.work_width) == (400, 700)
    assert abs(g.bbox_height - 360) <= 4 and abs(g.bbox_width - 360) <= 4
    # Disc fills the output: the centre is bright, corners are black.
    c = OUT_SIZE // 2
    assert pre.image[c - 40:c + 40, c - 40:c + 40].astype(int).sum(axis=2).mean() > 100
    assert pre.image[0, 0].sum() == 0


def test_black_image_has_no_retina():
    with pytest.raises(NoRetinaFoundError):
        preprocess(np.zeros((500, 500, 3), np.uint8))


def test_corrupted_file_raises(tmp_path):
    bad = tmp_path / "bad.jpg"
    bad.write_bytes(b"not an image at all")
    with pytest.raises(Exception):
        load_rgb(str(bad))


def test_exif_rotation_is_applied(tmp_path):
    rgb = synthetic_fundus(300, 500, seed=6)
    path = tmp_path / "rot.jpg"
    im = Image.fromarray(rgb)
    exif = im.getexif()
    exif[0x0112] = 6  # rotate 90 CW on display
    im.save(path, exif=exif)
    assert load_rgb(str(path)).shape[:2] == (500, 300)


def test_quality_accepts_fundus_and_rejects_non_fundus():
    ok, _ = Q.assess(synthetic_fundus(800, 800, seed=7))
    assert ok.status == Q.ACCEPTED, ok.reasons
    grey, _ = Q.assess(np.full((600, 600, 3), 128, np.uint8))
    assert grey.status == Q.UNSUPPORTED
    noise = np.random.default_rng(0).integers(0, 256, (600, 600, 3), dtype=np.uint8)
    assert Q.assess(noise)[0].status == Q.UNSUPPORTED


def test_quality_flags_dark_small_and_blurred_images():
    dark, _ = Q.assess(synthetic_fundus(700, 700, seed=8, brightness=0.15))
    assert dark.status == Q.RETAKE and "too_dark" in dark.reasons
    small, _ = Q.assess(synthetic_fundus(200, 200, seed=9))
    assert "too_small" in small.reasons
    blurred, _ = Q.assess(synthetic_fundus(700, 700, seed=10, blur=True))
    assert "blurred" in blurred.reasons


def test_fit_thresholds_bounds_false_rejection_on_training_data():
    metrics = [Q.assess(synthetic_fundus(600, 600, seed=s))[0].metrics for s in range(40)]
    t = Q.fit_thresholds(metrics, low_pct=5, high_pct=95)
    rejected = sum(Q.decide(m, t).status != Q.ACCEPTED for m in metrics)
    assert rejected <= 40 * 0.3


GOLDEN = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "test", "resources", "golden")


@pytest.mark.skipif(not os.path.isdir(GOLDEN), reason="golden files not generated")
def test_committed_golden_files_match_reference():
    import json
    for name in open(os.path.join(GOLDEN, "cases.txt")).read().split():
        from netra_ml.golden import read_gz
        meta = json.load(open(os.path.join(GOLDEN, f"{name}.json")))
        rgb = read_gz(os.path.join(GOLDEN, f"{name}_input.rgb.gz")).reshape(meta["height"], meta["width"], 3)
        result, pre = Q.assess(rgb)
        assert result.status == meta["status"]
        if pre is not None:
            expected = read_gz(os.path.join(GOLDEN, f"{name}_expected.rgb.gz"))
            assert np.array_equal(pre.image.ravel(), expected)
