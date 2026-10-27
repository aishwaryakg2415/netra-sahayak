package com.sih.netrasahayak.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sih.netrasahayak.R
import com.sih.netrasahayak.database.ScreeningEntity
import com.sih.netrasahayak.model.DrClass
import com.sih.netrasahayak.model.Gender
import com.sih.netrasahayak.ui.components.DisclaimerBanner
import com.sih.netrasahayak.ui.components.ErrorView
import com.sih.netrasahayak.ui.components.LabelledSection
import com.sih.netrasahayak.ui.components.LoadingView
import com.sih.netrasahayak.ui.components.ResultCard
import com.sih.netrasahayak.ui.components.RetinalImageViewer
import com.sih.netrasahayak.ui.components.ScreenPadding
import com.sih.netrasahayak.ui.components.formatDateTime
import com.sih.netrasahayak.ui.components.imageModel

/**
 * One saved screening, re-opened from history.
 */
@Composable
fun HistoryDetailScreen(
    screening: ScreeningEntity?,
    loading: Boolean,
    modifier: Modifier = Modifier
) {
    if (loading) {
        LoadingView(title = stringResource(R.string.opening_screening), modifier = modifier)
        return
    }

    if (screening == null) {
        ErrorView(
            message = stringResource(R.string.screening_not_found),
            modifier = modifier.fillMaxSize()
        )
        return
    }

    val drClass = DrClass.fromApi(screening.prediction)
    val recommendationText = if (drClass != null && (screening.recommendation.isBlank() || screening.recommendation == drClass.defaultRecommendation)) {
        drClass.getLocalizedRecommendation()
    } else {
        screening.recommendation
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding, vertical = 20.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.patient_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = screening.patientId,
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(Modifier.height(18.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    LabelledSection(
                        label = stringResource(R.string.age_label),
                        value = stringResource(R.string.age_years_format, screening.age)
                    )
                    LabelledSection(
                        label = stringResource(R.string.gender_label),
                        value = Gender.fromApiValue(screening.gender)?.getLocalizedLabel()
                            ?: stringResource(R.string.gender_not_recorded)
                    )
                }

                Spacer(Modifier.height(16.dp))

                LabelledSection(
                    label = stringResource(R.string.years_with_diabetes_label),
                    value = screening.diabetesDurationYears?.let { "$it" }
                        ?: stringResource(R.string.gender_not_recorded)
                )

                Spacer(Modifier.height(16.dp))

                LabelledSection(
                    label = stringResource(R.string.screened_on_label),
                    value = formatDateTime(screening.createdAt)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        if (drClass != null) {
            ResultCard(drClass = drClass, confidence = screening.confidence)
        } else {
            Text(
                text = screening.prediction,
                style = MaterialTheme.typography.headlineSmall
            )
        }

        Spacer(Modifier.height(28.dp))

        Text(
            text = stringResource(R.string.why_this_result),
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.why_result_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(18.dp))

        RetinalImageViewer(
            originalModel = imageModel(screening.imagePath),
            heatmapModel = imageModel(screening.heatmapUrl)
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.recommended_action),
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = recommendationText,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(18.dp)
            )
        }

        Spacer(Modifier.height(24.dp))

        DisclaimerBanner()

        Spacer(Modifier.height(24.dp))
    }
}
