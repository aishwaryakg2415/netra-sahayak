"""Keras model definition, tf.data input pipeline, training loop and LiteRT export.

Architecture: ImageNet-pretrained backbone -> GlobalAveragePooling -> Dropout ->
Dense(5) logits. Because the head is GAP + linear, the class activation map
CAM_c = ReLU(sum_k W[k, c] * A_k) is exactly Grad-CAM for this model (the
Grad-CAM channel weights are dy_c/dA_k averaged = W[k, c] / (H * W)). The
exported model therefore has two outputs - `logits` and `features` - and the
Android app computes the explanation from the real feature maps and the real
head weights (cam_weights.bin). `verify_cam_equals_gradcam` checks this claim.
"""

from __future__ import annotations

import json
import os
import time

import numpy as np

import keras
import tensorflow as tf
from keras import layers

from .metrics import NUM_CLASSES, quadratic_weighted_kappa

ARCHITECTURES = {
    "effnetv2b0": lambda **kw: keras.applications.EfficientNetV2B0(include_preprocessing=True, **kw),
    "effnetv2b2": lambda **kw: keras.applications.EfficientNetV2B2(include_preprocessing=True, **kw),
    "mobilenetv3l": lambda **kw: keras.applications.MobileNetV3Large(include_preprocessing=True,
                                                                     minimalistic=False, **kw),
}


def build(arch: str, size: int, weights: str | None = "imagenet", dropout: float = 0.3):
    base = ARCHITECTURES[arch](include_top=False, weights=weights, input_shape=(size, size, 3))
    features = base.output
    x = layers.GlobalAveragePooling2D(name="gap")(features)
    x = layers.Dropout(dropout, name="head_dropout")(x)
    logits = layers.Dense(NUM_CLASSES, name="logits", dtype="float32")(x)
    model = keras.Model(base.input, logits, name=f"netra_{arch}")
    explain = keras.Model(base.input, [logits, features], name=f"netra_{arch}_explain")
    return model, explain, base


# ----------------------------------------------------------------- data

def _decode(path, size):
    img = tf.io.decode_image(tf.io.read_file(path), channels=3, expand_animations=False)
    img = tf.cast(img, tf.float32)
    img.set_shape([size, size, 3])
    return img


def _augment(img):
    # Geometry: flips and 90-degree rotations keep every lesion intact.
    img = tf.image.random_flip_left_right(img)
    img = tf.image.random_flip_up_down(img)
    img = tf.image.rot90(img, k=tf.random.uniform([], 0, 4, dtype=tf.int32))
    # Mild photometric jitter only, so subtle lesions are not erased.
    scale = tf.random.uniform([], 0.9, 1.1)
    shift = tf.random.uniform([], -10.0, 10.0)
    black = tf.reduce_sum(img, axis=-1, keepdims=True) <= 60.0  # keep the background black
    img = tf.where(black, img, tf.clip_by_value(img * scale + shift, 0.0, 255.0))
    return img


def make_dataset(paths, labels, size, batch_size, training, weights=None, seed=42):
    paths = np.asarray(paths)
    labels = np.asarray(labels, dtype=np.int32)
    if training:
        ws = np.asarray(weights, dtype=np.float32)
        ds = tf.data.Dataset.from_tensor_slices((paths, labels, ws))
        ds = ds.shuffle(len(paths), seed=seed, reshuffle_each_iteration=True)
        ds = ds.map(lambda p, y, w: (_augment(_decode(p, size)), y, w),
                    num_parallel_calls=tf.data.AUTOTUNE)
        ds = ds.batch(batch_size, drop_remainder=True)
    else:
        ds = tf.data.Dataset.from_tensor_slices((paths, labels))
        ds = ds.map(lambda p, y: (_decode(p, size), y), num_parallel_calls=tf.data.AUTOTUNE)
        ds = ds.batch(batch_size)
    return ds.prefetch(tf.data.AUTOTUNE)


def class_weights(labels, power: float = 0.5) -> dict[int, float]:
    """Inverse-frequency class weights tempered by `power` (0.5 = square root)."""
    counts = np.bincount(np.asarray(labels), minlength=NUM_CLASSES).astype(np.float64)
    w = (counts.sum() / (NUM_CLASSES * np.maximum(counts, 1))) ** power
    w = w / (w * counts).sum() * counts.sum()  # mean weight over samples = 1
    return {i: float(w[i]) for i in range(NUM_CLASSES)}


def focal_loss(gamma: float = 2.0):
    def loss(y_true, logits):
        y = tf.cast(tf.reshape(y_true, [-1]), tf.int32)
        logp = tf.nn.log_softmax(tf.cast(logits, tf.float32))
        lp = tf.gather(logp, y, batch_dims=1)
        return -tf.pow(1.0 - tf.exp(lp), gamma) * lp
    return loss


# ------------------------------------------------------------- training

class QwkCheckpoint(keras.callbacks.Callback):
    """Evaluates validation QWK each epoch and keeps the best weights."""

    def __init__(self, val_ds, val_labels, path, log):
        super().__init__()
        self.val_ds, self.val_labels, self.path, self.log = val_ds, np.asarray(val_labels), path, log
        self.best = -np.inf

    def on_epoch_end(self, epoch, logs=None):
        logits = self.model.predict(self.val_ds, verbose=0)
        qwk = quadratic_weighted_kappa(self.val_labels, logits.argmax(1))
        acc = float((logits.argmax(1) == self.val_labels).mean())
        if logs is not None:
            logs["val_qwk"], logs["val_acc"] = qwk, acc
        improved = qwk > self.best
        if improved:
            self.best = qwk
            self.model.save_weights(self.path)
        self.log(f"  epoch {epoch + 1}: loss={logs.get('loss', float('nan')):.4f} "
                 f"val_qwk={qwk:.4f} val_acc={acc:.4f}{' *best*' if improved else ''}")


def train(exp: dict, train_items, val_items, out_dir: str, log, seed: int = 42) -> dict:
    """exp: {name, arch, size, loss, epochs, batch_size, lr}. items: (paths, labels)."""
    keras.utils.set_random_seed(seed)
    keras.mixed_precision.set_global_policy("mixed_float16")
    size, bs = exp["size"], exp["batch_size"]
    model, _, base = build(exp["arch"], size)

    cw = class_weights(train_items[1])
    sample_w = np.array([cw[int(y)] for y in train_items[1]], dtype=np.float32)
    train_ds = make_dataset(*train_items, size, bs, training=True, weights=sample_w, seed=seed)
    val_ds = make_dataset(*val_items, size, bs, training=False)
    loss = (keras.losses.SparseCategoricalCrossentropy(from_logits=True)
            if exp["loss"] == "ce" else focal_loss(2.0))

    run_dir = os.path.join(out_dir, exp["name"])
    os.makedirs(run_dir, exist_ok=True)
    best_path = os.path.join(run_dir, "best.weights.h5")
    ckpt = QwkCheckpoint(val_ds, val_items[1], best_path, log)
    csv_log = keras.callbacks.CSVLogger(os.path.join(run_dir, "history.csv"), append=True)
    t0 = time.time()

    # Stage 1: train only the new head (1 epoch) so random weights don't wreck the backbone.
    base.trainable = False
    model.compile(optimizer=keras.optimizers.AdamW(1e-3, weight_decay=1e-5), loss=loss)
    log(f"[{exp['name']}] stage 1 (head only)")
    model.fit(train_ds, epochs=1, callbacks=[ckpt, csv_log], verbose=2)

    # Stage 2: fine-tune everything with cosine decay.
    base.trainable = True
    steps = len(train_items[0]) // bs
    schedule = keras.optimizers.schedules.CosineDecay(exp["lr"], decay_steps=steps * exp["epochs"],
                                                      alpha=0.02)
    model.compile(optimizer=keras.optimizers.AdamW(schedule, weight_decay=1e-5), loss=loss)
    log(f"[{exp['name']}] stage 2 (full fine-tune, {exp['epochs']} epochs, {steps} steps/epoch)")
    backup = keras.callbacks.BackupAndRestore(os.path.join(run_dir, "backup"))
    model.fit(train_ds, epochs=exp["epochs"], callbacks=[ckpt, csv_log, backup], verbose=2)

    info = {"name": exp["name"], "best_val_qwk": float(ckpt.best), "train_seconds": time.time() - t0,
            "class_weights": cw, "best_weights": best_path, **exp}
    with open(os.path.join(run_dir, "train_info.json"), "w") as fh:
        json.dump(info, fh, indent=2)
    keras.backend.clear_session()
    return info


def predict_logits(exp: dict, weights_path: str, paths, batch_size: int = 32) -> np.ndarray:
    return predict_many(exp, weights_path, [paths], batch_size)[0]


def predict_many(exp: dict, weights_path: str, path_lists, batch_size: int = 32) -> list[np.ndarray]:
    """Builds the model once (one XLA compile) and predicts several image lists."""
    keras.mixed_precision.set_global_policy("float32")
    model, _, _ = build(exp["arch"], exp["size"], weights=None)
    model.load_weights(weights_path)
    outs = []
    for paths in path_lists:
        if len(paths) == 0:
            outs.append(np.zeros((0, NUM_CLASSES)))
            continue
        ds = make_dataset(paths, np.zeros(len(paths), np.int32), exp["size"], batch_size, training=False)
        outs.append(model.predict(ds, verbose=0).astype(np.float64))
    keras.backend.clear_session()
    return outs


# --------------------------------------------------------------- export

def export_tflite(exp: dict, weights_path: str, out_dir: str, representative_paths, log) -> dict:
    """Exports FP32 / FP16 / dynamic-range INT8 / full INT8 variants with a
    two-output signature: image[1,S,S,3] float32 0-255 -> {logits, features}."""
    keras.mixed_precision.set_global_policy("float32")
    model, explain, _ = build(exp["arch"], exp["size"], weights=None)
    model.load_weights(weights_path)
    size = exp["size"]

    # Keras 3 models must be exported through keras.export.ExportArchive;
    # tf.saved_model.save cannot traverse them.
    def serve(image):
        logits, features = explain(image, training=False)
        return {"logits": logits, "features": features}

    saved = os.path.join(out_dir, "saved_model")
    archive = keras.export.ExportArchive()
    archive.track(explain)
    archive.add_endpoint(name="serving_default", fn=serve,
                         input_signature=[tf.TensorSpec([1, size, size, 3], tf.float32, name="image")])
    archive.write_out(saved)

    def rep_data():
        for p in representative_paths:
            img = np.asarray(_decode(tf.constant(p), size).numpy(), dtype=np.float32)[None]
            yield {"image": img}

    variants = {}
    for name in ("fp32", "fp16", "int8_dynamic", "int8_full"):
        conv = tf.lite.TFLiteConverter.from_saved_model(saved, signature_keys=["serving_default"])
        if name != "fp32":
            conv.optimizations = [tf.lite.Optimize.DEFAULT]
        if name == "fp16":
            conv.target_spec.supported_types = [tf.float16]
        if name == "int8_full":
            conv.representative_dataset = rep_data
        try:
            blob = conv.convert()
        except Exception as exc:  # report, never hide
            log(f"  export {name} FAILED: {exc!r}")
            continue
        path = os.path.join(out_dir, f"netra_dr_{name}.tflite")
        with open(path, "wb") as fh:
            fh.write(blob)
        variants[name] = path
        log(f"  export {name}: {len(blob) / 1e6:.2f} MB")

    dense = model.get_layer("logits")
    kernel, bias = [np.asarray(v, dtype=np.float32) for v in dense.get_weights()]
    keras.backend.clear_session()
    return {"variants": variants, "cam_kernel": kernel, "cam_bias": bias}


class TfliteRunner:
    def __init__(self, path: str, threads: int = 4):
        self.interp = tf.lite.Interpreter(model_path=path, num_threads=threads)
        self.runner = self.interp.get_signature_runner("serving_default")

    def __call__(self, image_uint8: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        out = self.runner(image=image_uint8.astype(np.float32)[None])
        return np.asarray(out["logits"][0], np.float64), np.asarray(out["features"][0], np.float32)


def cam_from_features(features: np.ndarray, kernel: np.ndarray, cls: int) -> np.ndarray:
    cam = np.tensordot(features.astype(np.float64), kernel[:, cls].astype(np.float64), axes=([2], [0]))
    return np.maximum(cam, 0.0)


def gradcam_keras(exp: dict, weights_path: str, images_uint8, classes) -> list[np.ndarray]:
    """Textbook Grad-CAM with GradientTape, used to verify the CAM shortcut."""
    keras.mixed_precision.set_global_policy("float32")
    model, explain, _ = build(exp["arch"], exp["size"], weights=None)
    model.load_weights(weights_path)
    cams = []
    for image, cls in zip(images_uint8, classes):
        x = tf.constant(image.astype(np.float32)[None])
        with tf.GradientTape() as tape:
            logits, feats = explain(x, training=False)
            target = logits[0, int(cls)]
        grads = tape.gradient(target, feats)
        alpha = tf.reduce_mean(grads, axis=(1, 2))[0]
        cams.append(tf.nn.relu(tf.reduce_sum(feats[0] * alpha, axis=-1)).numpy().astype(np.float64))
    keras.backend.clear_session()
    return cams
