package com.sih.netrasahayak.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sih.netrasahayak.R
import com.sih.netrasahayak.model.DrClass
import com.sih.netrasahayak.ui.theme.accentColor
import com.sih.netrasahayak.ui.theme.containerColor

/**
 * The headline of the result screen.
 *
 * Wording is deliberately non-diagnostic: "AI Screening Result" and
 * "Referral recommended", never "diagnosis".
 */
@Composable
fun ResultCard(
    drClass: DrClass,
    confidence: Float,
    modifier: Modifier = Modifier,
    title: String? = null
) {
    val accent = drClass.accentColor()
    val displayTitle = title ?: stringResource(R.string.ai_screening_result)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = drClass.containerColor())
    ) {
        Column(modifier = Modifier.padding(20.dp)) {

            Text(
                text = displayTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))

            Text(
                text = drClass.getLocalizedDisplayName(),
                style = MaterialTheme.typography.headlineMedium,
                color = accent
            )

            androidx.compose.foundation.layout.Spacer(Modifier.height(18.dp))

            ConfidenceIndicator(confidence = confidence, accentColor = accent)

            androidx.compose.foundation.layout.Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (drClass.referralSuggested) Icons.Filled.WarningAmber
                    else Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(28.dp)
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                Text(
                    text = if (drClass.referralSuggested) stringResource(R.string.referral_recommended)
                    else stringResource(R.string.no_referral_suggested),
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
                )
            }
        }
    }
}
