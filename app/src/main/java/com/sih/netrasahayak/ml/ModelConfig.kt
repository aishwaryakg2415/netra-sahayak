package com.sih.netrasahayak.ml

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Parsed form of assets/model/model_config.json, written by the training
 * pipeline (ml/netra_ml/pipeline.py). The model, its preprocessing constants,
 * calibration, decision thresholds and quality thresholds travel together and
 * are version-locked by the SHA-256 checksums in [files].
 */
data class ModelConfig(
    val schema: Int,
    @SerializedName("model_version") val modelVersion: String,
    val architecture: String,
    val quantization: String,
    val files: Files,
    val input: Input,
    val outputs: Map<String, List<Int>>,
    val preprocessing: Preprocessing,
    val classes: List<String>,
    val calibration: Calibration,
    val decision: Decision,
    @SerializedName("quality_thresholds") val qualityThresholds: Quality
) {
    data class FileRef(val name: String, val sha256: String, val shape: List<Int>? = null)
    data class Files(val model: FileRef, @SerializedName("cam_weights") val camWeights: FileRef)
    data class Input(val signature: String, val name: String, val shape: List<Int>)
    data class Preprocessing(
        @SerializedName("work_max_side") val workMaxSide: Int,
        @SerializedName("mask_threshold") val maskThreshold: Int,
        @SerializedName("out_size") val outSize: Int
    )
    data class Calibration(val temperature: Double)
    data class Decision(
        @SerializedName("referable_min_grade") val referableMinGrade: Int,
        @SerializedName("referable_threshold") val referableThreshold: Double,
        @SerializedName("urgent_threshold") val urgentThreshold: Double,
        @SerializedName("review_min_confidence") val reviewMinConfidence: Double
    )
    data class Quality(
        @SerializedName("min_side_px") val minSidePx: Int,
        @SerializedName("retina_fraction_min") val retinaFractionMin: Double,
        @SerializedName("corner_dark_min") val cornerDarkMin: Double,
        @SerializedName("red_ratio_min") val redRatioMin: Double,
        @SerializedName("brightness_min") val brightnessMin: Double,
        @SerializedName("brightness_max") val brightnessMax: Double,
        @SerializedName("saturated_max") val saturatedMax: Double,
        @SerializedName("sharpness_min") val sharpnessMin: Double
    )

    fun decisionConfig() = DecisionConfig(
        decision.referableMinGrade, decision.referableThreshold,
        decision.urgentThreshold, decision.reviewMinConfidence
    )

    fun qualityThresholds() = QualityThresholds(
        qualityThresholds.minSidePx, qualityThresholds.retinaFractionMin, qualityThresholds.cornerDarkMin,
        qualityThresholds.redRatioMin, qualityThresholds.brightnessMin, qualityThresholds.brightnessMax,
        qualityThresholds.saturatedMax, qualityThresholds.sharpnessMin
    )

    /** Rejects a config whose preprocessing differs from what this app version implements. */
    fun checkCompatible() {
        require(schema == 1) { "unsupported model_config schema $schema" }
        require(preprocessing.outSize == RetinaPreprocessor.OUT_SIZE &&
            preprocessing.workMaxSide == RetinaPreprocessor.WORK_MAX_SIDE &&
            preprocessing.maskThreshold == RetinaPreprocessor.MASK_THRESHOLD) {
            "model preprocessing does not match this app version"
        }
        require(classes.size == 5) { "expected 5 classes" }
        require(input.shape == listOf(1, preprocessing.outSize, preprocessing.outSize, 3)) { "unexpected input shape" }
        require(calibration.temperature > 0) { "invalid temperature" }
    }

    companion object {
        fun parse(json: String): ModelConfig = Gson().fromJson(json, ModelConfig::class.java)
    }
}
