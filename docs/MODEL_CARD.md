# Model card: Netra Sahayak DR screening model

| | |
|---|---|
| Model version | `effnetv2b2_ce-fp16-20261027` |
| Task | 5-grade diabetic retinopathy (ICDR 0–4) from one colour fundus photograph, plus a derived referable-DR flag |
| Architecture | EfficientNetV2-B2 (ImageNet-pretrained) → global average pooling → dropout 0.3 → dense(5) |
| Input | 384×384 RGB, float32 0–255, after the retina-crop preprocessing in `ml/netra_ml/preprocess.py` |
| Outputs | 5 logits, plus 12×12×1408 feature maps used for the explanation heatmap |
| Deployed format | LiteRT FP16, 17.4 MB (`app/src/main/assets/model/netra_dr.tflite`) |
| Training | Kaggle, 1× NVIDIA T4, 5.0 h for the whole pipeline (2026-10-27) |
| Status | **Research / hackathon prototype. Not clinically validated.** |

All numbers below come from `ml/results/*.json`, which the training run wrote. They were
not edited by hand.

## Intended use

Decision *support* for trained health workers (e.g. ASHAs) who screen people with diabetes,
in settings where the image comes from a **fundus camera**. The app shows a screening
suggestion and a referral recommendation, and always tells the user that a qualified
professional must confirm the diagnosis.

**Not intended for:**
- diagnosis
- deciding not to refer someone who has symptoms
- photos of the eye taken with a normal phone camera
- children
- eye diseases other than DR (glaucoma, AMD, DME and so on are not detected)

## Training data

47,245 gradable images were pooled from APTOS 2019, EyePACS (Kaggle 2015) and DDR
(train + valid), after the leakage and retina-detection exclusions below. They were split
by patient where possible (EyePACS) and stratified by grade, in an 80/10/10 ratio:

| Split | Images | Grades 0 / 1 / 2 / 3 / 4 |
|---|---|---|
| Train | 37,759 | 25,430 / 2,581 / 7,471 / 966 / 1,311 |
| Validation | 4,726 | 3,184 / 320 / 936 / 123 / 163 |
| Internal test | 4,760 | held out from the same pool |

292 training images were removed because they were near-duplicates of an evaluation image.
22 images failed retina detection and were excluded. See
[DATASETS_AND_PROVENANCE.md](DATASETS_AND_PROVENANCE.md) for sources, licences and leakage
controls.

Class imbalance was handled with square-root inverse-frequency class weights. Augmentation
was limited to flips, 90° rotations and mild brightness/contrast jitter.

## Model selection (validation set only)

| Candidate | Val QWK | Val accuracy | Val ECE |
|---|---|---|---|
| EfficientNetV2-B0, cross-entropy | 0.827 | 0.818 | 0.021 |
| MobileNetV3-Large, cross-entropy | 0.836 | 0.825 | 0.032 |
| EfficientNetV2-B0, focal loss (temperature-scaled) | 0.831 | 0.825 | 0.052 |
| **EfficientNetV2-B2, cross-entropy (selected)** | **0.838** | 0.815 | **0.017** |

The selection rule was the highest validation QWK, and the test sets played no part in it.
The four candidates are close; the confidence intervals overlap.
**Calibration:** temperature scaling would have been applied only if it lowered
out-of-fold ECE on the validation set. For the selected model it did not (0.020 → 0.024),
so T = 1.

## Evaluation

**Referable DR** = grade ≥ 2. The referable score is the summed probability of grades
2–4. Its threshold (0.164) was chosen on validation data to reach 90% sensitivity.

| Test set | n | Accuracy | QWK (95% CI) | Referable sens. | Referable spec. | ROC-AUC | PPV | NPV | ECE |
|---|---|---|---|---|---|---|---|---|---|
| Internal (APTOS+EyePACS+DDR pool) | 4,760 | 0.826 | 0.837 (0.818–0.853) | 0.905 | 0.891 | 0.959 | 0.744 | 0.964 | 0.024 |
| DDR official test | 3,759 | 0.847 | 0.876 (0.863–0.889) | 0.871 | 0.956 | 0.977 | 0.941 | 0.901 | 0.061 |
| **IDRiD** (external) | 455 | 0.611 | 0.851 (0.825–0.878) | 0.954 | 0.808 | 0.979 | 0.909 | 0.897 | 0.155 |
| **Messidor-2** (external, adjudicated) | 1,744 | 0.754 | 0.799 (0.771–0.823) | 0.912 | 0.814 | 0.948 | 0.636 | 0.963 | 0.067 |

PPV and NPV depend on prevalence. They hold for each test set's own case mix, not for a
screening population.

**Per-class sensitivity on Messidor-2** (No DR / Mild / Moderate / Severe / PDR):
0.864 / 0.437 / 0.752 / 0.493 / 0.571.

**Dangerous error: severe or proliferative DR predicted as "No DR".**
- Internal test: 9 of 291 grade-3/4 eyes (3.1%).
- Messidor-2: 1 of 110.
- IDRiD: 0.

The referral rule in the app also refers when the summed referable probability exceeds the
threshold, even if the top grade is No DR or Mild. That catches some, not all, of these
cases.

### Known weaknesses
- **Mild DR is poorly separated**: sensitivity is 0.33–0.44. Mild cases are mostly called
  No DR or Moderate. Because Mild is not referable, this matters less for referral than for
  grading.
- **Calibration degrades out of distribution.** ECE is 0.155 on IDRiD and 0.067 on
  Messidor-2. On IDRiD, accuracy is low (0.611) while QWK stays high, which means the model
  is mostly one grade off rather than badly wrong.
- **No population-specific validation.** IDRiD is Indian data, but it is small (455 images)
  and comes from one site. Performance on images from the cameras and populations where the
  app will be used is unknown.

## Image-quality gate

The gate uses deterministic heuristics, with thresholds set at the 0.5th/99.5th percentiles
of training images (`quality_gate.json`):

| Set | Accepted | Retake | Unsupported |
|---|---|---|---|
| Training / validation / test images (gradable) | 96.7–100% | 0.5–2.2% | 0–1.4% |
| **DDR images graded *ungradable*** | **72.5%** | 12.2% | 15.2% |

**The gate rejects only 27.5% of genuinely ungradable images.** It catches blank, very dark
or bright, blurred, too-small and non-fundus inputs. It is **not** a reliable gradability
classifier. A trained quality model is the main open improvement. Until one exists, the
low-confidence review rule and the human-review wording are the remaining safeguards.

## Quantization (measured on 1,000 validation images)

| Variant | Size | QWK | Referable sens. | Agreement with FP32 | x86 latency* |
|---|---|---|---|---|---|
| FP32 | 34.7 MB | 0.813 | 0.896 | 100% | 58 ms |
| **FP16 (deployed)** | **17.4 MB** | 0.813 | 0.896 | 100% | 58 ms |
| INT8 dynamic range | 9.6 MB | 0.812 | 0.883 | 97.4% | 108 ms |
| INT8 full integer | 10.4 MB | 0.738 | 0.738 | 84.9% | 60 ms |

\*Measured on the Kaggle CPU, **not on a phone**. On-device latency has not been measured yet.
The deployment rule picks the smallest variant within 0.01 QWK and referable sensitivity of
FP32 with ≥ 98% agreement. INT8 dynamic missed the agreement bar, and full INT8 lost too much
accuracy.

## Explainability

The head is GAP → dense, so the class activation map
CAM_c = ReLU(Σ_k W[k,c]·A_k) is mathematically Grad-CAM up to a positive scale. On 12
validation images, the CAM computed from the deployed FP16 model correlates with
GradientTape Grad-CAM on the FP32 Keras model at **Pearson r = 1.000 (min 1.000)**
(`explainability_check.json`). The heatmap shows which regions drove the score. It is
**not** a lesion segmentation and does not show that a prediction is correct.

## Decision rules (provisional)

`ReferralPolicy` (Kotlin) and `referral.py` (Python) apply these rules, in order:
1. An ungradable image produces no grade.
2. Grade ≥ 2, or referable score ≥ 0.164, means a referral.
3. Grade 3/4, or severe score ≥ 0.198, means an urgent referral.
4. A top-class probability < 0.5 adds human review.
5. Otherwise, routine rescreening.

These thresholds come from validation data. **No ophthalmologist has reviewed them**, and
they must not be presented as a clinical protocol.

## Ethical and licence considerations

- **Training data terms:** the data comes from competitions and research releases that mostly
  restrict use to non-commercial research. The ImageNet-pretrained weights carry similar
  terms. Redistribution of the trained weights beyond research or demo use has not been
  cleared.
- **Privacy:** the app processes images on the phone. It never uploads them, and it keeps
  patient data out of cloud backups.
- **Clinical deployment:** before any clinical use, this model needs prospective validation
  on the target devices and population, under ethics approval and with ophthalmologist
  oversight.
