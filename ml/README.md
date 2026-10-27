# Netra Sahayak – ML pipeline

This folder covers everything that happens **before** the app gets its model: dataset
loading, leakage-safe splits, training, comparing architectures, calibration,
evaluation, LiteRT export, quantization and golden files. The Android app never runs
any of it. The app ships only the exported artifacts in `app/src/main/assets/model/`.

```
netra_ml/
  preprocess.py   reference preprocessing (Kotlin port: RetinaPreprocessor.kt)
  quality.py      image-quality gate      (Kotlin port: ImageQualityAssessor.kt)
  referral.py     decision rules          (Kotlin port: ReferralPolicy.kt)
  datasets.py     APTOS / EyePACS / DDR / IDRiD / Messidor-2 loaders
  splits.py       patient-grouped stratified split, near-duplicate detection
  metrics.py      QWK, per-class sens/spec, referable-DR stats, ECE, Brier, bootstrap CIs
  calibration.py  temperature scaling (fitted on validation only)
  tf_model.py     Keras models, tf.data input, training, LiteRT export, CAM / Grad-CAM
  pipeline.py     end-to-end run (what the Kaggle kernel executes)
  golden.py       golden files for the Kotlin parity tests
kaggle/build_kernel.py   packages this folder into a Kaggle GPU kernel
install_model.py         copies a finished run into the Android app (checks SHA-256)
tests/                   pytest suite
```

## Minimum requirements per stage

| Stage | Where | Needs |
|---|---|---|
| Unit tests, golden files | any laptop (8 GB RAM is fine) | Python 3.10+, `numpy pillow pytest scikit-learn` |
| Training + export | Kaggle (T4 GPU) or Colab | free GPU quota; the datasets attached as Kaggle inputs |
| Android build | laptop | JDK 17, Android SDK 34 |

Nothing in this folder loads a whole dataset into memory. Images are streamed with
`tf.data`, and the preprocessing cache lives on the training machine's disk.

## 1. Local tests (Windows)

```bash
cd ml
python -m pip install numpy pillow pytest scikit-learn
python -m pytest -q tests
```

`test_tflite_model.py` is skipped locally. It needs TensorFlow and a trained model, and it
runs inside the Kaggle pipeline.

## 2. Training on Kaggle (recommended)

One-time setup:
1. On kaggle.com, verify your phone number (needed for GPU access).
2. Join the **APTOS 2019 Blindness Detection** competition (needed for its data).
3. Under **Settings → API Tokens**, create a token. Save it as
   `C:\Users\<you>\.kaggle\access_token`, with the token alone on one line.
4. Install the Kaggle CLI:
   ```bash
   python -m pip install kaggle
   ```

Then:

```bash
# Optional: a small run that exercises every stage
python ml/kaggle/build_kernel.py --user <kaggle-username> --smoke --push
# The real run
python ml/kaggle/build_kernel.py --user <kaggle-username> --push
# When finished:
python -m kaggle kernels output <kaggle-username>/netra-dr-train -p kaggle_output
python ml/install_model.py kaggle_output/out
```

The kernel attaches the datasets listed in `build_kernel.py`, runs `netra_ml.pipeline`,
and writes `out/` with the following contents:
- `android_assets/`: the model, `cam_weights.bin` and `model_config.json`
- `reports/`: comparison, quantization, quality gate, explainability check, split manifest, logs
- `golden/`: on-device parity cases
- `runs/`: training histories and the selected checkpoint

The kernel runs at most 12 hours. Experiments that would exceed `--train-budget-hours`
are skipped and logged. Training resumes from `BackupAndRestore` checkpoints if a run is
restarted with the same output folder.

## 3. Training on Google Colab

```python
!git clone <your fork> netra && cd netra/ml
# Mount or download the datasets, then:
!python -m netra_ml.pipeline --input /content/datasets --out /content/out --cache /content/cache
```

`datasets.discover_roots` finds each dataset by its annotation file, so any folder layout
works as long as the original file names are kept.

## 4. Using your own dataset paths

Write a small script that builds the `roots` dict yourself, then call
`datasets.load_all(roots)`:

```python
roots = {"aptos2019": "D:/data/aptos", "messidor2_images": "D:/data/messidor2",
         "messidor2_grades": "D:/data/messidor2"}
```

Recognised keys: `aptos2019`, `eyepacs`, `ddr`, `idrid`, `messidor2_images`,
`messidor2_grades`.

## Reproducibility

- Seed 42 everywhere: splits, shuffling, Keras.
- Split assignments and dedup hashes are saved in `reports/split_manifest.csv`.
- `model_config.json` records the architecture, quantization, calibration temperature,
  thresholds and SHA-256 checksums of the shipped files.
