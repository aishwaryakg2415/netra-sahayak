package com.sih.netrasahayak.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sih.netrasahayak.R

/** The three ways of looking at one screened eye. */
enum class ImageViewMode(val label: String, @StringRes val labelRes: Int) {
    ORIGINAL("ORIGINAL", R.string.tab_original),
    HEATMAP("HEATMAP", R.string.tab_heatmap),
    OVERLAY("OVERLAY", R.string.tab_overlay);

    @Composable
    fun getLocalizedLabel(): String = stringResource(labelRes)
}

/**
 * Explainable-AI viewer.
 */
@Composable
fun RetinalImageViewer(
    originalModel: Any?,
    heatmapModel: Any?,
    modifier: Modifier = Modifier,
    overlayAlpha: Float = 0.55f
) {
    val hasHeatmap = heatmapModel != null
    var mode by remember(heatmapModel) { mutableStateOf(ImageViewMode.ORIGINAL) }

    Column(modifier = modifier.fillMaxWidth()) {

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when {
                originalModel == null -> Text(
                    text = stringResource(R.string.image_not_available),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )

                mode == ImageViewMode.HEATMAP && hasHeatmap -> AsyncImage(
                    model = heatmapModel,
                    contentDescription = "Grad-CAM heatmap",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                mode == ImageViewMode.OVERLAY && hasHeatmap -> {
                    AsyncImage(
                        model = originalModel,
                        contentDescription = "Retinal image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    AsyncImage(
                        model = heatmapModel,
                        contentDescription = "Grad-CAM heatmap overlay",
                        contentScale = ContentScale.Fit,
                        alpha = overlayAlpha,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                else -> AsyncImage(
                    model = originalModel,
                    contentDescription = "Retinal image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))

        ModeToggleRow(
            selected = mode,
            enabledModes = if (hasHeatmap) ImageViewMode.entries else listOf(ImageViewMode.ORIGINAL),
            onSelect = { mode = it }
        )

        if (!hasHeatmap) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.explanation_not_provided),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModeToggleRow(
    selected: ImageViewMode,
    enabledModes: List<ImageViewMode>,
    onSelect: (ImageViewMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(14.dp)
            )
    ) {
        ImageViewMode.entries.forEach { item ->
            val isEnabled = enabledModes.contains(item)
            val isSelected = item == selected && isEnabled

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.background
                    )
                    .clickable(enabled = isEnabled) { onSelect(item) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.getLocalizedLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.onPrimary
                        isEnabled -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}
