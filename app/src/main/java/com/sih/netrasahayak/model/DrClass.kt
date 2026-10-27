package com.sih.netrasahayak.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sih.netrasahayak.R

/**
 * The five diabetic-retinopathy grades the screening model can report.
 */
enum class DrClass(
    val apiLabel: String,
    val displayName: String,
    val shortName: String,
    val severity: Int,
    val defaultRecommendation: String,
    @StringRes val nameRes: Int,
    @StringRes val shortNameRes: Int,
    @StringRes val recommendationRes: Int
) {
    NO_DR(
        apiLabel = "No Diabetic Retinopathy",
        displayName = "No Diabetic Retinopathy",
        shortName = "No DR",
        severity = 0,
        defaultRecommendation = "No signs found. Repeat screening after one year.",
        nameRes = R.string.dr_no_dr_name,
        shortNameRes = R.string.dr_no_dr_short,
        recommendationRes = R.string.dr_no_dr_recommendation
    ),
    MILD(
        apiLabel = "Mild Diabetic Retinopathy",
        displayName = "Mild Diabetic Retinopathy",
        shortName = "Mild DR",
        severity = 1,
        defaultRecommendation = "Repeat screening in 6 to 12 months. Advise blood sugar control.",
        nameRes = R.string.dr_mild_name,
        shortNameRes = R.string.dr_mild_short,
        recommendationRes = R.string.dr_mild_recommendation
    ),
    MODERATE(
        apiLabel = "Moderate Diabetic Retinopathy",
        displayName = "Moderate Diabetic Retinopathy",
        shortName = "Moderate DR",
        severity = 2,
        defaultRecommendation = "Clinical evaluation by an ophthalmologist is recommended.",
        nameRes = R.string.dr_moderate_name,
        shortNameRes = R.string.dr_moderate_short,
        recommendationRes = R.string.dr_moderate_recommendation
    ),
    SEVERE(
        apiLabel = "Severe Diabetic Retinopathy",
        displayName = "Severe Diabetic Retinopathy",
        shortName = "Severe DR",
        severity = 3,
        defaultRecommendation = "Referral recommended. Please arrange an eye specialist visit soon.",
        nameRes = R.string.dr_severe_name,
        shortNameRes = R.string.dr_severe_short,
        recommendationRes = R.string.dr_severe_recommendation
    ),
    PROLIFERATIVE(
        apiLabel = "Proliferative Diabetic Retinopathy",
        displayName = "Proliferative Diabetic Retinopathy",
        shortName = "Proliferative DR",
        severity = 4,
        defaultRecommendation = "Urgent referral recommended. Please arrange an eye specialist visit.",
        nameRes = R.string.dr_proliferative_name,
        shortNameRes = R.string.dr_proliferative_short,
        recommendationRes = R.string.dr_proliferative_recommendation
    );

    val referralSuggested: Boolean get() = severity >= 2

    @Composable
    fun getLocalizedDisplayName(): String = stringResource(nameRes)

    @Composable
    fun getLocalizedShortName(): String = stringResource(shortNameRes)

    @Composable
    fun getLocalizedRecommendation(): String = stringResource(recommendationRes)

    companion object {
        fun fromApi(raw: String?): DrClass? {
            if (raw.isNullOrBlank()) return null
            val text = raw.trim().lowercase().replace('_', ' ').replace('-', ' ')

            text.toIntOrNull()?.let { index ->
                return entries.firstOrNull { it.severity == index }
            }

            return when {
                text.contains("prolifer") -> PROLIFERATIVE
                text.contains("severe") -> SEVERE
                text.contains("moderate") -> MODERATE
                text.contains("mild") -> MILD
                text.startsWith("no") || text.contains("no dr") ||
                    text.contains("normal") || text.contains("no diabetic") -> NO_DR
                else -> null
            }
        }
    }
}
