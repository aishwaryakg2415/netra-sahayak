# Implementation status

The labels follow the definition of done in the project brief:
- **Implemented**: the code exists.
- **Tested**: an automated test was run and passed.
- **Trained / Evaluated**: real runs produced real numbers.
- **Deployable**: the model is packaged and the APK builds.
- **Offline-verified**: inference was run on a device with networking off.
- **Clinically validated**: never claimed here.

Last updated: 2026-10-27.

## Summary

| Area | Status |
|---|---|
| Offline on-device inference (Android) | Implemented · unit-tested on the JVM · APK builds with the model bundled · **not yet run on a device** |
| Preprocessing parity (Python ↔ Kotlin) | Implemented · **Tested**: golden files, exact match (`GoldenParityTest`) |
| Image-quality gate | Implemented · Tested (Python + Kotlin) · rejection rates measured, see `ml/results/quality_gate.json` |
| Referral rules | Implemented · Tested (safety cases in both languages) · thresholds **provisional** |
| Explainability (CAM = Grad-CAM for the GAP head) | Implemented · checked against GradientTape Grad-CAM on Kaggle, see `ml/results/explainability_check.json` |
| Local persistence (Room v2) | Implemented · migration test written (`MigrationTest`) · **not yet run on a device** |
| Training pipeline | Implemented · two Kaggle smoke runs passed end to end |
| Model training | See "Training" below |
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
| `OfflineInferenceTest`, `MigrationTest` (instrumentation) | No device or emulator in the dev session (8 GB laptop, ~0.5 GB RAM free) | Connect a phone, then `bash tools/verify_offline.sh` |
| On-device latency / memory | Needs a phone | The same script logs timing. `ScreeningResult.processingMillis` is stored per screening |
| GPU delegate path | Needs a phone with a supported GPU | Covered by `cpuFallbackProducesSameScores` |

## Training

_Filled in from the real Kaggle run (`ml/results/`). Until that run finishes, no trained
model exists and the app reports "AI model not installed" instead of producing results._

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
5. **Translations need native review.** All 8 languages (English, Hindi, Marathi, Tamil,
   Telugu, Bengali, Gujarati, Spanish) translate every string, including the AI errors,
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
