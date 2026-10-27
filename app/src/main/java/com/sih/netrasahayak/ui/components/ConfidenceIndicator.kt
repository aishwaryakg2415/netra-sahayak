package com.sih.netrasahayak.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sih.netrasahayak.R

/**
 * "Confidence: 91%" plus a thick bar. Large enough to read at arm's length.
 *
 * Confidence is how sure the model is - not how severe the disease is - so the
 * label says exactly that underneath.
 */
@Composable
fun ConfidenceIndicator(
    confidence: Float,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val fraction = confidence.coerceIn(0f, 1f)
    val percent = (fraction * 100).toInt()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.confidence),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.headlineSmall,
                color = accentColor
            )
        }

        androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))

        // Simple two-layer bar: track + filled portion.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(9.dp)
                )
        ) {
            Layout(
                content = {
                    Box(
                        modifier = Modifier
                            .height(18.dp)
                            .background(color = accentColor, shape = RoundedCornerShape(9.dp))
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { measurables, constraints ->
                val barWidth = (constraints.maxWidth * fraction).toInt().coerceAtLeast(0)
                val placeable = measurables.first().measure(
                    constraints.copy(minWidth = barWidth, maxWidth = barWidth)
                )
                layout(constraints.maxWidth, placeable.height) {
                    placeable.placeRelative(0, 0)
                }
            }
        }

        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.confidence_tooltip),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp)
        )
    }
}
