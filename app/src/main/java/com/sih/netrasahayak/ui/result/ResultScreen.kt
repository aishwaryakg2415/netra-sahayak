package com.sih.netrasahayak.ui.result

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sih.netrasahayak.R
import com.sih.netrasahayak.model.ScreeningResult
import com.sih.netrasahayak.ui.components.DisclaimerBanner
import com.sih.netrasahayak.ui.components.PrimaryButton
import com.sih.netrasahayak.ui.components.ResultCard
import com.sih.netrasahayak.ui.components.RetinalImageViewer
import com.sih.netrasahayak.ui.components.ScreenPadding
import com.sih.netrasahayak.ui.components.imageModel

/**
 * The screening suggestion, its explanation, and what to do next.
 */
@Composable
fun ResultScreen(
    result: ScreeningResult,
    imageUri: Uri?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    savedLocally: Boolean = true
) {
    val recommendationText = if (result.recommendation.isBlank() || result.recommendation == result.drClass.defaultRecommendation) {
        result.drClass.getLocalizedRecommendation()
    } else {
        result.recommendation
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding, vertical = 20.dp)
    ) {
        ResultCard(drClass = result.drClass, confidence = result.confidence)

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
            originalModel = imageUri,
            heatmapModel = imageModel(result.heatmapUrl)
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

        if (savedLocally) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.saved_on_phone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(28.dp))

        PrimaryButton(
            text = stringResource(R.string.done),
            icon = Icons.Filled.Done,
            onClick = onDone
        )

        Spacer(Modifier.height(24.dp))
    }
}
