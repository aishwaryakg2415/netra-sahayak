package com.sih.netrasahayak.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sih.netrasahayak.R

/**
 * The minimum information needed for a screening record.
 * Entered entirely offline - nothing here requires a network connection.
 */
data class PatientDetails(
    val patientId: String,
    val age: Int,
    val gender: Gender? = null,
    val diabetesDurationYears: Int? = null
)

enum class Gender(
    val label: String,
    val apiValue: String,
    @StringRes val labelRes: Int
) {
    FEMALE("Female", "female", R.string.gender_female),
    MALE("Male", "male", R.string.gender_male),
    OTHER("Other", "other", R.string.gender_other);

    @Composable
    fun getLocalizedLabel(): String = stringResource(labelRes)

    companion object {
        fun fromApiValue(value: String?): Gender? =
            entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) }
    }
}

/** Result of validating the patient form. Used to show field-level messages. */
data class PatientFormErrors(
    val patientIdErrorRes: Int? = null,
    val ageErrorRes: Int? = null,
    val ageMin: Int = 1,
    val ageMax: Int = 120,
    val diabetesDurationErrorRes: Int? = null
) {
    val hasErrors: Boolean
        get() = patientIdErrorRes != null || ageErrorRes != null || diabetesDurationErrorRes != null
}

object PatientValidator {

    const val MIN_AGE = 1
    const val MAX_AGE = 120

    fun validate(
        patientId: String,
        ageText: String,
        diabetesDurationText: String
    ): PatientFormErrors {
        val idErrorRes = when {
            patientId.isBlank() -> R.string.err_enter_patient_id
            patientId.trim().length < 2 -> R.string.err_patient_id_too_short
            else -> null
        }

        val age = ageText.trim().toIntOrNull()
        val ageErrorRes = when {
            ageText.isBlank() -> R.string.err_enter_patient_age
            age == null -> R.string.err_age_must_be_number
            age < MIN_AGE || age > MAX_AGE -> R.string.err_age_range
            else -> null
        }

        val duration = diabetesDurationText.trim().toIntOrNull()
        val durationErrorRes = when {
            diabetesDurationText.isBlank() -> null
            duration == null -> R.string.err_years_must_be_number
            duration < 0 || duration > MAX_AGE -> R.string.err_realistic_years
            age != null && duration > age -> R.string.err_years_exceed_age
            else -> null
        }

        return PatientFormErrors(
            patientIdErrorRes = idErrorRes,
            ageErrorRes = ageErrorRes,
            ageMin = MIN_AGE,
            ageMax = MAX_AGE,
            diabetesDurationErrorRes = durationErrorRes
        )
    }
}
