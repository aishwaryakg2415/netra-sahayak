package com.sih.netrasahayak.ui.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sih.netrasahayak.R
import com.sih.netrasahayak.ui.components.PrimaryButton
import com.sih.netrasahayak.ui.components.ScreenPadding

/**
 * Offline-first sync.
 */
@Composable
fun SyncScreen(
    isOnline: Boolean,
    pendingCount: Int,
    syncState: SyncState,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding, vertical = 24.dp)
    ) {
        StatusCard(
            icon = if (isOnline) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
            label = stringResource(R.string.internet_connection),
            value = if (isOnline) stringResource(R.string.status_connected) else stringResource(R.string.status_not_connected),
            highlight = isOnline
        )

        Spacer(Modifier.height(18.dp))

        StatusCard(
            icon = Icons.Filled.CloudSync,
            label = stringResource(R.string.pending_screenings),
            value = when (pendingCount) {
                0 -> stringResource(R.string.pending_none)
                1 -> stringResource(R.string.pending_one)
                else -> stringResource(R.string.pending_multiple, pendingCount)
            },
            highlight = pendingCount == 0
        )

        Spacer(Modifier.height(36.dp))

        if (syncState is SyncState.Syncing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 4.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(16.dp))
                Text(
                    text = stringResource(R.string.sending_screenings),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        } else {
            PrimaryButton(
                text = stringResource(R.string.sync_now),
                icon = Icons.Filled.CloudSync,
                enabled = pendingCount > 0,
                onClick = onSyncNow
            )
        }

        val message = when (syncState) {
            is SyncState.Done -> {
                val summary = syncState.summary
                when {
                    summary.uploaded == 0 && summary.failed == 0 ->
                        stringResource(R.string.sync_nothing_to_send)
                    summary.failed == 0 ->
                        stringResource(R.string.sync_success, summary.uploaded)
                    else ->
                        stringResource(
                            R.string.sync_partial,
                            summary.uploaded,
                            summary.failed,
                            summary.error?.getLocalizedMessage() ?: stringResource(R.string.please_try_again_later)
                        )
                }
            }

            is SyncState.Failed -> syncState.error.getLocalizedMessage()
            else -> null
        }

        if (message != null) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = if (syncState is SyncState.Failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(36.dp))

        Text(
            text = stringResource(R.string.offline_sync_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    highlight: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
