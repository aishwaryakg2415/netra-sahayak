package com.sih.netrasahayak.ml

/**
 * Transparent decision-support rules, kept separate from the neural network.
 * Kotlin port of ml/netra_ml/referral.py.
 *
 * PROVISIONAL: thresholds come from model_config.json and were chosen on
 * validation data to reach a target sensitivity. They have not been reviewed by
 * an ophthalmologist and are not a clinical protocol.
 *
 * Safety properties (unit-tested):
 *  - this policy only ever sees ACCEPTED images; ungradable images never reach it,
 *    so they can never produce a "No DR" outcome
 *  - grade >= referableMinGrade always refers; grade 3/4 always refers urgently
 *  - a No DR / Mild argmax with high referable probability is referred AND flagged
 *    for review instead of being reassured
 *  - low confidence is flagged for human review
 */
enum class DecisionCategory { URGENT_REFERRAL, REFERRAL, HUMAN_REVIEW, ROUTINE_RESCREEN }

data class DecisionConfig(
    val referableMinGrade: Int,
    val referableThreshold: Double,
    val urgentThreshold: Double,
    val reviewMinConfidence: Double
)

data class Decision(
    val grade: Int,
    val referable: Boolean,
    val urgent: Boolean,
    val needsReview: Boolean,
    val category: DecisionCategory,
    val reasons: List<String>
)

object ReferralPolicy {

    fun decide(probs: FloatArray, cfg: DecisionConfig): Decision {
        require(probs.size == 5) { "expected 5 class probabilities" }
        var grade = 0
        for (i in 1 until probs.size) if (probs[i] > probs[grade]) grade = i
        var refScore = 0.0
        for (i in cfg.referableMinGrade until 5) refScore += probs[i]
        val urgentScore = probs[3].toDouble() + probs[4]
        val reasons = mutableListOf<String>()

        val referable = grade >= cfg.referableMinGrade || refScore >= cfg.referableThreshold
        if (referable && grade < cfg.referableMinGrade) reasons += "referable_probability_above_threshold"
        val urgent = grade >= 3 || urgentScore >= cfg.urgentThreshold
        if (urgent && grade < 3) reasons += "severe_probability_above_threshold"
        val lowConfidence = probs[grade] < cfg.reviewMinConfidence
        if (lowConfidence) reasons += "low_model_confidence"
        val needsReview = lowConfidence || (referable && grade < cfg.referableMinGrade)

        val category = when {
            urgent -> DecisionCategory.URGENT_REFERRAL
            referable -> DecisionCategory.REFERRAL
            needsReview -> DecisionCategory.HUMAN_REVIEW
            else -> DecisionCategory.ROUTINE_RESCREEN
        }
        return Decision(grade, referable, urgent, needsReview, category, reasons)
    }

    fun softmax(logits: FloatArray, temperature: Double): FloatArray {
        val z = DoubleArray(logits.size) { logits[it] / temperature }
        val max = z.max()
        val e = DoubleArray(z.size) { kotlin.math.exp(z[it] - max) }
        val sum = e.sum()
        return FloatArray(z.size) { (e[it] / sum).toFloat() }
    }
}
