package com.sih.netrasahayak.ui.preview

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sih.netrasahayak.R
import com.sih.netrasahayak.ui.components.ImagePreviewCard
import com.sih.netrasahayak.ui.components.LoadingView
import com.sih.netrasahayak.ui.components.PrimaryButton
import com.sih.netrasahayak.ui.components.ScreenPadding
import com.sih.netrasahayak.ui.components.SecondaryButton
import com.sih.netrasahayak.ui.screening.AnalysisState

/**
 * Shows the captured or selected image and waits for an explicit decision.
 */
@Composable
fun ImagePreviewScreen(
    imageUri: Uri?,
    analysis: AnalysisState,
    onAnalyze: () -> Unit,
    onRetake: () -> Unit,
    onChooseAnother: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (analysis is AnalysisState.Analyzing) {
        LoadingView(
            title = stringResource(R.string.analyzing_retinal_image),
            subtitle = stringResource(R.string.please_wait),
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding, vertical = 20.dp)
    ) {
        Text(
            text = stringResource(R.string.check_the_image),
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.check_image_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        ImagePreviewCard(model = imageUri)

        if (analysis is AnalysisState.Error) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = analysis.error.getLocalizedMessage(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(28.dp))

        PrimaryButton(
            text = if (analysis is AnalysisState.Error) stringResource(R.string.try_again) else stringResource(R.string.analyze_image),
            icon = Icons.Filled.Science,
            enabled = imageUri != null,
            onClick = onAnalyze
        )

        Spacer(Modifier.height(16.dp))

        SecondaryButton(
            text = stringResource(R.string.retake),
            icon = Icons.Filled.PhotoCamera,
            onClick = onRetake
        )

        Spacer(Modifier.height(16.dp))

        SecondaryButton(
            text = stringResource(R.string.choose_another),
            icon = Icons.Filled.PhotoLibrary,
            onClick = onChooseAnother
        )

        Spacer(Modifier.height(24.dp))
    }
}
