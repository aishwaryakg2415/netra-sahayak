package com.sih.netrasahayak.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sih.netrasahayak.R
import com.sih.netrasahayak.ui.components.DisclaimerBanner
import com.sih.netrasahayak.ui.components.LanguageSelectionDialog
import com.sih.netrasahayak.ui.components.PrimaryButton
import com.sih.netrasahayak.ui.components.ScreenPadding
import com.sih.netrasahayak.ui.components.SecondaryButton
import com.sih.netrasahayak.util.LanguageManager

/**
 * Landing screen. Three actions, nothing else - the first thing an ASHA worker
 * sees must be obvious.
 */
@Composable
fun HomeScreen(
    onStartScreening: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showLanguageDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val currentLanguage = remember(showLanguageDialog) { LanguageManager.getCurrentLanguage(context) }

    if (showLanguageDialog) {
        LanguageSelectionDialog(onDismissRequest = { showLanguageDialog = false })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { showLanguageDialog = true }
            ) {
                Icon(
                    imageVector = Icons.Filled.Translate,
                    contentDescription = stringResource(R.string.change_language)
                )
                Spacer(Modifier.height(0.dp))
                Text(
                    text = "  ${currentLanguage.nativeName}",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = stringResource(R.string.app_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(40.dp))

        PrimaryButton(
            text = stringResource(R.string.start_new_screening),
            icon = Icons.Filled.RemoveRedEye,
            onClick = onStartScreening
        )

        Spacer(Modifier.height(20.dp))

        SecondaryButton(
            text = stringResource(R.string.screening_history),
            icon = Icons.Filled.History,
            onClick = onOpenHistory
        )

        Spacer(Modifier.height(20.dp))

        SecondaryButton(
            text = stringResource(R.string.sync_data),
            icon = Icons.Filled.CloudSync,
            onClick = onOpenSync
        )

        Spacer(Modifier.height(40.dp))

        DisclaimerBanner()
    }
}
