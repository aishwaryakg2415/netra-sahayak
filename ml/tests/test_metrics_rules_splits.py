import numpy as np
import pytest

from netra_ml import referral as R
from netra_ml.calibration import fit_temperature
from netra_ml.datasets import load_ddr, load_eyepacs
from netra_ml.metrics import (binary_stats, expected_calibration_error, full_report,
                              quadratic_weighted_kappa, softmax, threshold_for_sensitivity)
from netra_ml.splits import grouped_stratified_split, near_duplicate_pairs

CFG = {"referable_min_grade": 2, "referable_threshold": 0.3, "urgent_threshold": 0.25,
       "review_min_confidence": 0.5}


def test_qwk_matches_sklearn():
    sk = pytest.importorskip("sklearn.metrics")
    rng = np.random.default_rng(0)
    y = rng.integers(0, 5, 300)
    p = np.clip(y + rng.integers(-1, 2, 300), 0, 4)
    assert quadratic_weighted_kappa(y, p) == pytest.approx(sk.cohen_kappa_score(y, p, weights="quadratic"))
    assert quadratic_weighted_kappa(y, y) == pytest.approx(1.0)


def test_softmax_rows_sum_to_one():
    p = softmax(np.random.default_rng(1).normal(size=(50, 5)) * 10)
    assert np.allclose(p.sum(1), 1.0) and (p >= 0).all()


def test_binary_stats_and_threshold():
    s = binary_stats([1, 1, 0, 0], [1, 0, 0, 1])
    assert s["sensitivity"] == 0.5 and s["specificity"] == 0.5 and s["npv"] == 0.5
    y = np.array([1] * 10 + [0] * 10)
    score = np.linspace(0, 1, 20)
    t = threshold_for_sensitivity(y, score, 0.9)
    assert binary_stats(y, score >= t)["sensitivity"] >= 0.9


def test_ece_zero_for_perfectly_calibrated_onehot():
    y = np.array([0, 1, 2, 3, 4])
    assert expected_calibration_error(np.eye(5)[y], y) == pytest.approx(0.0)


def test_temperature_scaling_recovers_known_temperature():
    rng = np.random.default_rng(2)
    true_logits = rng.normal(size=(4000, 5)) * 2
    p = softmax(true_logits)
    y = np.array([rng.choice(5, p=row) for row in p])
    assert fit_temperature(true_logits * 3.0, y) == pytest.approx(3.0, rel=0.1)


def test_full_report_counts_severe_missed_as_no_dr():
    probs = np.eye(5)[[0, 0, 2, 4]]
    rep = full_report(probs, np.array([4, 0, 2, 4]), referable_threshold=0.5, n_boot=10)
    assert rep["severe_or_pdr_predicted_no_dr"] == 1
    assert rep["referable"]["fn"] == 1


# ---- medical-safety rules

def test_referable_grade_triggers_referral():
    d = R.decide(np.array([0.05, 0.05, 0.8, 0.05, 0.05]), CFG)
    assert d.referable and d.category in (R.REFERRAL, R.URGENT_REFERRAL)


def test_severe_and_pdr_are_urgent_and_never_downgraded():
    for grade in (3, 4):
        p = np.full(5, 0.025)
        p[grade] = 0.9
        d = R.decide(p, CFG)
        assert d.grade == grade and d.urgent and d.category == R.URGENT_REFERRAL


def test_no_dr_argmax_with_high_referable_mass_is_not_reassured():
    d = R.decide(np.array([0.45, 0.15, 0.2, 0.1, 0.1]), CFG)
    assert d.grade == 0 and d.referable and d.needs_review
    assert d.category != R.ROUTINE_RESCREEN


def test_low_confidence_requires_human_review():
    d = R.decide(np.array([0.40, 0.38, 0.12, 0.06, 0.04]), {**CFG, "referable_threshold": 0.5,
                                                             "urgent_threshold": 0.5})
    assert d.needs_review and d.category == R.HUMAN_REVIEW


def test_confident_no_dr_is_routine():
    d = R.decide(np.array([0.95, 0.03, 0.01, 0.005, 0.005]), CFG)
    assert d.category == R.ROUTINE_RESCREEN and not d.needs_review


# ---- leakage controls

def test_grouped_split_never_splits_a_patient():
    rng = np.random.default_rng(3)
    groups = [f"p{i // 2}" for i in range(1000)]
    labels = list(rng.integers(0, 5, 1000))
    s = grouped_stratified_split(labels, groups, {"train": 0.8, "val": 0.1, "test": 0.1}, seed=1)
    per_group = {}
    for g, name in zip(groups, s):
        per_group.setdefault(g, set()).add(name)
    assert all(len(v) == 1 for v in per_group.values())
    assert 0.7 < s.count("train") / 1000 < 0.9
    assert s == grouped_stratified_split(labels, groups, {"train": 0.8, "val": 0.1, "test": 0.1}, seed=1)


def test_near_duplicates_found():
    hashes = [0x0123456789ABCDEF, 0x0123456789ABCDEE, 0xFEDCBA9876543210, 0x0123456789ABCDE8]
    pairs = near_duplicate_pairs(hashes, max_distance=3, bits=64)
    assert (0, 1) in pairs and (0, 3) in pairs and all(2 not in p for p in pairs)


def test_dhash_separates_different_eyes_but_matches_recompressed_copy():
    import io
    from PIL import Image
    from netra_ml.golden import synthetic_fundus
    from netra_ml.preprocess import preprocess
    from netra_ml.splits import NEAR_DUPLICATE_BITS, dhash

    def gray(rgb):
        return (preprocess(rgb).image.astype(np.uint16).sum(axis=2) // 3).astype(np.uint8)

    a = synthetic_fundus(700, 700, seed=11)
    buf = io.BytesIO()
    Image.fromarray(a).save(buf, format="JPEG", quality=80)
    a_jpeg = np.asarray(Image.open(io.BytesIO(buf.getvalue())).convert("RGB"))
    b = synthetic_fundus(700, 700, seed=12)
    ha, hj, hb = dhash(gray(a)), dhash(gray(a_jpeg)), dhash(gray(b))
    assert bin(ha ^ hj).count("1") <= NEAR_DUPLICATE_BITS
    assert bin(ha ^ hb).count("1") > 3 * NEAR_DUPLICATE_BITS


def test_dataset_loaders_read_annotation_files(tmp_path):
    (tmp_path / "eye" / "resized_train" / "resized_train").mkdir(parents=True)
    (tmp_path / "eye" / "trainLabels.csv").write_text("image,level\n10_left,0\n10_right,3\n")
    recs = load_eyepacs(str(tmp_path / "eye"))
    assert [r.label for r in recs] == [0, 3] and recs[0].patient_id == recs[1].patient_id
    ddr = tmp_path / "ddr" / "DR_grading"
    ddr.mkdir(parents=True)
    (ddr / "train.txt").write_text("a.jpg 0\nb.jpg 5\n")
    recs = load_ddr(str(tmp_path / "ddr"))
    assert [r.label for r in recs] == [0, 5] and recs[0].source_split == "train"
