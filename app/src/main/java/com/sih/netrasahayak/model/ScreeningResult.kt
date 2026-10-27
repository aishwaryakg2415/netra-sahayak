package com.sih.netrasahayak.model

/**
 * A completed AI screening, as shown on the result screen.
 *
 * This is the app's own domain model. It is produced by the on-device model
 * (LocalInferenceRepository), the optional backend, or the mock repository.
 * The UI never sees a network DTO or a raw tensor directly.
 */
data class ScreeningResult(
    val drClass: DrClass,
    /** Exactly what the engine said, kept for the record. */
    val rawPrediction: String,
    /**
     * 0.0 .. 1.0. The model's calibrated top-class score - a measure of model
     * certainty, NOT a verified probability that the patient has the disease.
     */
    val confidence: Float,
    /**
     * Location of the explanation heatmap, or null when none was produced.
     * May be an http(s) URL or a local file path.
     */
    val heatmapUrl: String?,
    val recommendation: String,

    // ---- Filled by the on-device engine; defaults keep other sources unchanged.

    /** Unique id of this screening (UUID). */
    val screeningId: String? = null,
    /** Calibrated per-class scores for grades 0..4. */
    val classScores: List<Float> = emptyList(),
    /** ImageQualityAssessor status name, e.g. ACCEPTED_FOR_SCREENING. */
    val qualityStatus: String? = null,
    /** Referable DR (grade >= 2 or summed referable score above threshold). Null when unknown. */
    val referable: Boolean? = null,
    val urgent: Boolean? = null,
    /** True when the result must be checked by a person before acting on it. */
    val needsReview: Boolean = false,
    /** ReferralPolicy category name. */
    val decisionCategory: String? = null,
    val modelVersion: String? = null,
    val processingMillis: Long? = null
) {
    val confidencePercent: Int get() = (confidence.coerceIn(0f, 1f) * 100).toInt()
    val hasHeatmap: Boolean get() = !heatmapUrl.isNullOrBlank()
}
