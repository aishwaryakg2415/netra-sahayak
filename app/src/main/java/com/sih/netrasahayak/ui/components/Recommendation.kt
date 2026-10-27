package com.sih.netrasahayak.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sih.netrasahayak.R
import com.sih.netrasahayak.model.DrClass

/**
 * The recommendation shown on the result and history screens, in the language
 * the user has selected *now*.
 *
 * On-device screenings store their ReferralPolicy category, so the advice is
 * rebuilt from string resources at display time; switching language later also
 * translates old history entries. Records without a category (backend / older
 * versions) fall back to the stored text, localised when it is a grade default.
 */
@Composable
fun localizedRecommendation(
    drClass: DrClass?,
    decisionCategory: String?,
    needsReview: Boolean,
    storedText: String
): String {
    val base = when (decisionCategory) {
        "URGENT_REFERRAL" -> stringResource(R.string.rec_urgent_referral)
        "REFERRAL" -> stringResource(R.string.rec_referral)
        "HUMAN_REVIEW" -> stringResource(R.string.rec_human_review)
        "ROUTINE_RESCREEN" -> drClass?.getLocalizedRecommendation() ?: storedText
        else -> if (drClass != null && (storedText.isBlank() || storedText == drClass.defaultRecommendation)) {
            drClass.getLocalizedRecommendation()
        } else {
            storedText
        }
    }
    return if (decisionCategory != null && needsReview && decisionCategory != "HUMAN_REVIEW") {
        base + stringResource(R.string.rec_review_suffix)
    } else {
        base
    }
}
