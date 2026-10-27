# Packaging the model into the Android app

The app bundles everything it needs for screening inside the APK. There is no runtime
download, no server and no INTERNET permission in release builds.

## What goes into `app/src/main/assets/model/`

| File | Produced by | Purpose |
|---|---|---|
| `netra_dr.tflite` | `tf_model.export_tflite` | LiteRT model. Signature `serving_default`: `image[1,384,384,3]` float32 RGB 0–255 → `logits[1,5]`, `features[1,h,w,k]` |
| `cam_weights.bin` | same | Classifier head weights `[k][5]`, float32 little-endian, used for the on-device explanation heatmap |
| `model_config.json` | `pipeline.py` | Model version, quantization, preprocessing constants, calibration temperature, decision thresholds, quality thresholds, SHA-256 of both files above |

`ml/install_model.py` copies these files. It refuses to install them if a checksum doesn't
match.

## Load-time safety checks (`DrClassifier.load`)

1. `model_config.json` must exist, or the error is `ModelNotInstalled`.
2. The schema version and preprocessing constants must match this app's Kotlin
   preprocessor, or the error is `ModelIntegrityFailed`. This stops a model trained with
   different preprocessing from running silently.
3. The SHA-256 of `netra_dr.tflite` and `cam_weights.bin` must match the config, or the
   error is `ModelIntegrityFailed`.
4. The GPU delegate is used only if `CompatibilityList` reports support. Any delegate
   failure falls back to the CPU (XNNPACK, 4 threads).

If any check fails, the user sees a plain error and **no result**. Nothing ever falls back
to a mock or a guess.

## Build

```bash
# Windows, from the repository root, with JDK 17
gradlew.bat :app:assembleRelease
```

The `.tflite` file is stored uncompressed (`androidResources.noCompress`) so it can be
memory-mapped. The FP16 model adds about 12–15 MB to the APK; see `ml/results/quantization.json` for the exact size.

### Windows build note

If Gradle fails with `Unable to establish loopback connection`, set this before building:

```bash
set JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\<you>\.gradle\udstmp
```

The folder must already exist. Java 16+ on Windows creates an internal socket in the
temp folder, and some Windows setups block that location. Use JDK 17 or 21, because
Gradle 8.7 does not run on Java 24/25.

## Verifying offline operation on a device

```bash
bash tools/verify_offline.sh
```

The script does the following:
1. Installs the app and its test APK.
2. Enables airplane mode and disables Wi-Fi and mobile data.
3. Confirms the device can't reach the internet.
4. Runs `OfflineInferenceTest` and `MigrationTest`, which include real inference on the
   golden images and a check that the scores match the training pipeline.
5. Restores connectivity.

It prints `OFFLINE VERIFICATION PASSED` only if the tests ran while the device was
offline.

## Inference modes (`INFERENCE_MODE` in `app/build.gradle.kts`)

| Value | Engine | Where allowed |
|---|---|---|
| `local` (default) | On-device LiteRT model | debug and release |
| `remote` | Optional FastAPI backend (`RemoteInferenceRepository`) | debug only; release has no INTERNET permission |
| `mock` | `MockInferenceRepository`, fake results for UI demos | debug only, never for real screening |

## Updating the model

Train a new run, then run `install_model.py`, rebuild and run the tests. The app always
uses the model bundled in its APK, so an update means shipping a new APK. There is no
over-the-air model download, and offline operation never depends on one.
