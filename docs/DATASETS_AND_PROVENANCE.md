# Datasets, provenance and licences

All training happened on Kaggle. Kaggle hosts the copies listed below; nothing was
downloaded to a laptop. Every image's dataset, original split, assigned split, quality
status and perceptual hash is recorded in `split_manifest.csv`, which the pipeline writes
to its output folder. File paths in that manifest are relative to the Kaggle input mount.

| Dataset | Kaggle source used | Images | Labels | Role here |
|---|---|---|---|---|
| APTOS 2019 Blindness Detection | competition `aptos2019-blindness-detection` (train split only) | 3,662 | ICDR grade 0–4, one grader per image | train / val / internal test |
| EyePACS (Kaggle 2015 DR Detection) | `tanlikesmath/diabetic-retinopathy-resized` | 35,126 | ICDR grade 0–4, one grader per image | train / val / internal test |
| DDR | `samriddhibagchi/ddr-dataset-credits-to-authors` | 13,673 | grade 0–4 plus 5 = ungradable | train/valid → pool; official test → external test; grade 5 → quality-gate test |
| IDRiD (grading) | `mariaherrerot/idrid-dataset` | 455 graded images | ICDR grade 0–4 | external test only |
| Messidor-2 | images: `mariaherrerot/messidor2preprocess`; grades: `google-brain/messidor2-dr-grades` | 1,744 | adjudicated ICDR grade (Krause et al. 2018) plus gradability | external test only; ungradable images → quality-gate test |

## Label harmonisation

All five datasets use (or map directly onto) the International Clinical DR scale:
0 No DR, 1 Mild NPDR, 2 Moderate NPDR, 3 Severe NPDR, 4 PDR. No remapping was needed.
DDR's grade 5 and Messidor-2's `adjudicated_gradable = 0` are **not DR labels**. Those
images are used only to measure how well the image-quality gate rejects ungradable photos.

Label quality differs between sources:
- APTOS and EyePACS were graded by a single clinician per image. EyePACS is known to have
  noticeable label noise.
- Messidor-2 uses Google's adjudicated reference standard, the most reliable labels here.
  That is why it is the headline external test set.
- IDRiD and DDR were graded by experts. The protocols are described in their papers.

## Leakage controls

1. **Patient grouping.** EyePACS file names encode the patient (`10_left`, `10_right`),
   so both eyes of a patient always go to the same split. APTOS, DDR and IDRiD publish no
   patient identifier, so for them each image is its own group. That is a known limitation:
   two images of the same patient could land in different splits.
2. **Near-duplicate removal.** A 64-bit difference hash is computed on every preprocessed
   image. Any training image within Hamming distance 3 of an image in *any* evaluation set
   is dropped from training. The number of drops is in `pipeline.log`.
3. **No tuning on test data.** The validation split alone drives:
   - model selection
   - temperature calibration
   - the referable and urgent thresholds

   The test sets are evaluated once, after those choices are fixed.
4. **Fixed seed (42)** and saved split manifests make the splits reproducible.
5. **Quality thresholds** are fitted on training images only.

## Licences and terms (check before any use beyond research)

| Source | Terms as understood (verify yourself) |
|---|---|
| APTOS 2019 | Kaggle competition rules. Data is for the competition and non-commercial research. |
| EyePACS 2015 | Kaggle competition rules. The resized copy is a third-party mirror, and its redistribution terms are unclear. |
| DDR | Released for research by Li et al., *Information Sciences* 2019. Cite the paper. The Kaggle copy is a third-party mirror. |
| IDRiD | CC BY 4.0 (IEEE DataPort). Cite Porwal et al. 2018. |
| Messidor-2 | Messidor program licence: research use with citation. Adjudicated grades: cite Krause et al., *Ophthalmology* 2018. |
| ImageNet-pretrained Keras weights | Keras code is Apache 2.0. The weights come from ImageNet, whose terms restrict use to non-commercial research. |

**Consequence:** the trained model is a **research / hackathon prototype**. Before bundling it
in an app that is distributed beyond a demo, confirm that every data source and the
pretrained weights allow redistributing derived weights for your intended use. Otherwise,
retrain on data you hold rights to.
