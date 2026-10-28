# Implementation status

The labels follow the definition of done in the project brief:
- **Implemented**: the code exists.
- **Tested**: an automated test was run and passed.
- **Trained / Evaluated**: real runs produced real numbers.
- **Deployable**: the model is packaged and the APK builds.
- **Offline-verified**: inference was run on a device with networking off.
- **Clinically validated**: never claimed here.

Last updated: 2026-10-28 (device verification added).

## Summary

| Area | Status |
|---|---|
| Offline on-device inference (Android) | Implemented · **Offline-verified on a phone** (A001T, Android 16, arm64) · release APK has no INTERNET permission |
| Preprocessing parity (Python ↔ Kotlin) | Implemented · **Tested**: golden files, exact match (`GoldenParityTest`) |
| Image-quality gate | Implemented · Tested (Python + Kotlin) · rejection rates measured, see `ml/results/quality_gate.json` |
| Referral rules | Implemented · Tested (safety cases in both languages) · thresholds **provisional** |
| Explainability (CAM = Grad-CAM for the GAP head) | Implemented · checked against GradientTape Grad-CAM on Kaggle, see `ml/results/explainability_check.json` |
| Local persistence (Room v2) | Implemented · **Tested on a phone**: v1 → v2 migration keeps existing history (`MigrationTest`) |
| Training pipeline | Implemented · two Kaggle smoke runs passed end to end |
| Model training | **Trained and evaluated** (see "Training"). FP16 model bundled in the APK |
| Clinical validation | **Not done.** No clinical evidence exists for this model. |

## Done and tested

- `ml/` Python test suite: 26 passed locally, 1 skipped (LiteRT model test; it needs
  TensorFlow). On Kaggle the full suite passed, model test included: 27 passed.
- Android JVM unit tests: 19 passed (incl. `LocalizationTest`: 3, every language complete).
  - `GoldenParityTest` (3): Kotlin preprocessing and quality metrics match the Python
    reference on 5 golden cases.
  - `ReferralPolicyTest` (7): ungradable images can't reach the policy; referable grades
    refer; severe/PDR are urgent; a No DR argmax with high referable mass is flagged, not
    reassured; low confidence goes to human review.
  - `ModelPipelineTest` (6): input shape, rejection of black/grey images, CAM arithmetic,
    heatmap alignment, config compatibility check.
- Baseline debug build before any change: passed.
- After the changes, debug, release and androidTest APKs all build with model assets bundled.
- Release APK, inspected with `aapt2 dump permissions`: **no INTERNET permission**. The
  model is stored uncompressed (memory-mappable).
- Patient data is excluded from cloud backup. Deleting a record also deletes its image and
  heatmap.
- Room schema v1 → v2 is an additive migration. The destructive fallback is removed.

## Implemented but not yet executed

| Item | Why not run | How to run |
|---|---|---|
| On-device memory use | Not profiled yet | Android Studio Profiler while screening |
| GPU delegate path | Needs a phone with a supported GPU | Covered by `cpuFallbackProducesSameScores` |

## Training

**Trained:** yes. One Kaggle T4 run on 2026-10-27 took 5.0 h and compared 4 candidates.
EfficientNetV2-B2 was selected on validation QWK. The details and every number are in
[docs/MODEL_CARD.md](docs/MODEL_CARD.md); the raw evidence is in `ml/results/`.

**Evaluated** on data never used for training or selection:

| Test set | n | QWK | Referable sens. / spec. |
|---|---|---|---|
| Internal held-out | 4,760 | 0.837 | 0.905 / 0.891 |
| DDR official test | 3,759 | 0.876 | 0.871 / 0.956 |
| IDRiD (external) | 455 | 0.851 | 0.954 / 0.808 |
| Messidor-2 (external, adjudicated) | 1,744 | 0.799 | 0.912 / 0.814 |

**Deployable:** yes. FP16 LiteRT, 17.4 MB, is bundled in `app/src/main/assets/model/`
with SHA-256 checks. Debug, release and androidTest APKs build; the release APK is 40.4 MB.
Quantization was measured: INT8 variants were rejected (97.4% / 84.9% agreement with FP32).

**Offline-verified:** **yes**, on 2026-10-28, on one phone (model A001T, Android 16, arm64-v8a).
The phone was put in airplane mode with Wi-Fi and mobile data off. A ping failed and the test log
recorded `network during test: offline`. The instrumentation suite then passed (`OK (7 tests)`; the
missing-model test is skipped by design because the model is bundled):
- **Golden images:** real inference on the three golden images matched the training pipeline's
  probabilities within 0.03, with identical grades (2, 3, 4), and wrote heatmap files.
- **Rejections:** a non-fundus image and a corrupted file were rejected without a grade.
- **CPU fallback:** GPU and CPU paths gave the same scores.
- **Migration:** the v1 → v2 database migration preserved an existing record.

**On-device speed:** the whole pipeline (decode, quality gate, preprocessing, FP16 inference and
heatmap) took **525–660 ms per image**. It ran on the CPU (XNNPACK); the GPU delegate was not used
on this phone. That is one device only; low-end phones may be slower.

**Bug found by the device test and fixed:** `RetinalImageDecoder` treated the deliberate `null`
from a bounds-only decode as failure. Every photo would have been rejected as an invalid image.
JVM unit tests could not see this, because they do not use Android's decoder.

**Clinically validated:** **no.**

Safety findings from the evaluation:
- The quality gate accepts **72.5% of DDR's ungradable images**. It is not a reliable
  gradability check (see the model card).
- Severe/PDR predicted as No DR: 9/291 on the internal test (3.1%), 1/110 on Messidor-2.
- Mild DR sensitivity is low (0.33–0.44).

## Known limitations and open items

1. **No clinical validation.** Referral thresholds come from validation data, not from an
   ophthalmologist-approved protocol.
2. **Phone-camera photos are not fundus photos.** The quality gate rejects obvious
   non-fundus images, but it was evaluated only on curated datasets. It can't detect every
   ungradable image. Measured rejection of DDR's ungradable images is reported in
   `quality_gate.json`.
3. **No patient identifiers** exist in APTOS, DDR or IDRiD, so patient-level leakage
   control applies only to EyePACS. Near-duplicate removal reduces the risk for the others.
4. **Data licences** are mostly research-only. See `docs/DATASETS_AND_PROVENANCE.md`.
5. **Translations need native review.** All 9 languages (English, Hindi, Marathi, Tamil,
   Telugu, Bengali, Gujarati, Kannada, Spanish) translate every string, including the AI errors,
   image-quality reasons and referral advice. `LocalizationTest` enforces complete
   coverage and matching placeholders. The AI strings were machine-written, though, and a
   native speaker with clinical vocabulary should review them before field use. Referral
   advice is localised at display time from the stored decision category, so history
   entries switch language with the app.
6. **LiteRT API.** The app uses the LiteRT 1.4.2 Interpreter API with signature runners,
   plus a GPU delegate and CPU/XNNPACK fallback. The newer CompiledModel API (LiteRT 2.x)
   is not used, to keep the stable signature runner and the delegate fallback. Migrating
   later is a change inside `DrClassifier.kt` only.
7. **Windows build quirk.** Gradle needs JDK 17/21 and
   `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<dir>`; see `docs/DEPLOYMENT.md`.
