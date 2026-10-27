"""Checks against an exported LiteRT model. Runs where TensorFlow and a model exist
(the Kaggle pipeline sets NETRA_TFLITE); skipped elsewhere and reported as skipped."""

import os

import numpy as np
import pytest

MODEL = os.environ.get("NETRA_TFLITE", "")
tf = pytest.importorskip("tensorflow")
pytestmark = pytest.mark.skipif(not os.path.isfile(MODEL), reason="NETRA_TFLITE not set")


def _run(image):
    interp = tf.lite.Interpreter(model_path=MODEL)
    return interp.get_signature_runner("serving_default")(image=image)


def test_signature_shapes_and_probabilities():
    from netra_ml.golden import synthetic_fundus
    from netra_ml.metrics import softmax
    from netra_ml.preprocess import OUT_SIZE, preprocess

    x = preprocess(synthetic_fundus(800, 800)).image.astype(np.float32)[None]
    out = _run(x)
    assert out["logits"].shape == (1, 5)
    assert out["features"].ndim == 4 and out["features"].shape[0] == 1
    assert np.isfinite(out["logits"]).all()
    p = softmax(out["logits"].astype(np.float64))
    assert np.allclose(p.sum(), 1.0)
    assert OUT_SIZE % out["features"].shape[1] == 0


def test_inference_is_deterministic():
    x = np.random.default_rng(0).uniform(0, 255, (1, 384, 384, 3)).astype(np.float32)
    assert np.array_equal(_run(x)["logits"], _run(x)["logits"])
