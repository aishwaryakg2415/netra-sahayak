package com.sih.netrasahayak.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Medical-safety rules of the decision layer (mirrors ml/tests/test_metrics_rules_splits.py). */
class ReferralPolicyTest {

    private val cfg = DecisionConfig(
        referableMinGrade = 2, referableThreshold = 0.3, urgentThreshold = 0.25, reviewMinConfidence = 0.5
    )

    private fun p(vararg v: Double) = FloatArray(v.size) { v[it].toFloat() }

    @Test
    fun referableGradeTriggersReferral() {
        val d = ReferralPolicy.decide(p(0.05, 0.05, 0.8, 0.05, 0.05), cfg)
        assertTrue(d.referable)
        assertTrue(d.category == DecisionCategory.REFERRAL || d.category == DecisionCategory.URGENT_REFERRAL)
    }

    @Test
    fun severeAndProliferativeAreUrgentAndNeverDowngraded() {
        for (grade in 3..4) {
            val probs = FloatArray(5) { 0.025f }.also { it[grade] = 0.9f }
            val d = ReferralPolicy.decide(probs, cfg)
            assertEquals(grade, d.grade)
            assertTrue(d.urgent)
            assertEquals(DecisionCategory.URGENT_REFERRAL, d.category)
        }
    }

    @Test
    fun noDrArgmaxWithHighReferableMassIsNotReassured() {
        val d = ReferralPolicy.decide(p(0.45, 0.15, 0.2, 0.1, 0.1), cfg)
        assertEquals(0, d.grade)
        assertTrue(d.referable)
        assertTrue(d.needsReview)
        assertNotEquals(DecisionCategory.ROUTINE_RESCREEN, d.category)
    }

    @Test
    fun lowConfidenceRequiresHumanReview() {
        val loose = cfg.copy(referableThreshold = 0.5, urgentThreshold = 0.5)
        val d = ReferralPolicy.decide(p(0.40, 0.38, 0.12, 0.06, 0.04), loose)
        assertTrue(d.needsReview)
        assertEquals(DecisionCategory.HUMAN_REVIEW, d.category)
    }

    @Test
    fun confidentNoDrIsRoutine() {
        val d = ReferralPolicy.decide(p(0.95, 0.03, 0.01, 0.005, 0.005), cfg)
        assertEquals(DecisionCategory.ROUTINE_RESCREEN, d.category)
        assertFalse(d.needsReview)
        assertFalse(d.referable)
    }

    @Test
    fun softmaxIsNormalisedAndTemperatureSoftens() {
        val logits = floatArrayOf(4f, 1f, 0f, -1f, -2f)
        val sharp = ReferralPolicy.softmax(logits, 1.0)
        val soft = ReferralPolicy.softmax(logits, 2.0)
        assertEquals(1.0, sharp.sum().toDouble(), 1e-5)
        assertEquals(1.0, soft.sum().toDouble(), 1e-5)
        assertTrue(soft[0] < sharp[0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongNumberOfClassesIsRejected() {
        ReferralPolicy.decide(p(0.5, 0.5), cfg)
    }
}
