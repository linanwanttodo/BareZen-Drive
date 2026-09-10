package com.linan.barezen_drive.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.data.update.UpdateStatus
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.platform.openInBrowser
import com.linan.barezen_drive.platform.reloadApp
import kotlinx.coroutines.launch

/**
 * Update row shown on every install channel (Android, iOS, desktop, web).
 *
 * The check goes through the connected server, not GitHub directly, so it works
 * from networks with no outbound access; the web client is offered a reload
 * because the server it talks to also serves its bundle.
 */
@Composable
internal fun UpdateCheckRow(
    currentVersion: String,
    checkUpdate: suspend () -> UpdateStatus,
) {
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<UpdateStatus?>(null) }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) {
                busy = true
                scope.launch {
                    status = runCatching { checkUpdate() }.getOrDefault(UpdateStatus.Failed)
                    busy = false
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(LocalStrings.current.checkForUpdates, style = MaterialTheme.typography.bodyLarge)
            Text(
                LocalStrings.current.currentVersion(currentVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }

    status?.let { outcome ->
        UpdateResultDialog(outcome = outcome, onDismiss = { status = null })
    }
}

@Composable
private fun UpdateResultDialog(outcome: UpdateStatus, onDismiss: () -> Unit) {
    when (outcome) {
        is UpdateStatus.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(LocalStrings.current.checkForUpdates) },
            text = { Text(LocalStrings.current.upToDate(outcome.version)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(LocalStrings.current.actionConfirm) }
            },
        )

        is UpdateStatus.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(LocalStrings.current.updateAvailable) },
            text = {
                Text(
                    if (outcome.reloadOnly) {
                        LocalStrings.current.reloadToUpdate(outcome.version)
                    } else {
                        LocalStrings.current.updateAvailableVersion(outcome.version)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    if (outcome.reloadOnly) {
                        reloadApp()
                    } else {
                        outcome.releaseUrl?.let(::openInBrowser)
                    }
                }) {
                    Text(
                        if (outcome.reloadOnly) LocalStrings.current.actionReload
                        else LocalStrings.current.openReleasePage,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(LocalStrings.current.actionCancel) }
            },
        )

        UpdateStatus.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(LocalStrings.current.checkForUpdates) },
            text = { Text(LocalStrings.current.checkFailedRetry) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(LocalStrings.current.actionConfirm) }
            },
        )
    }
}

/**
 * Web-only startup gate: the page loaded from the server can outlive a server
 * upgrade, leaving the tab on an old bundle. One silent check on first
 * composition offers a reload when the server reports a newer version, which
 * is how a server update reaches already-open browser clients. No-op offline
 * and on packaged clients (they use the Settings row instead).
 */
@Composable
internal fun WebUpdatePrompt(
    currentVersion: String,
    enabled: Boolean,
    checkUpdate: suspend () -> UpdateStatus,
) {
    var stale by remember { mutableStateOf<UpdateStatus.Available?>(null) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        val outcome = runCatching { checkUpdate() }.getOrNull()
        if (outcome is UpdateStatus.Available && outcome.reloadOnly) stale = outcome
    }
    stale?.let { outcome ->
        AlertDialog(
            onDismissRequest = { stale = null },
            title = { Text(LocalStrings.current.updateAvailable) },
            text = { Text(LocalStrings.current.reloadToUpdate(outcome.version)) },
            confirmButton = {
                TextButton(onClick = {
                    stale = null
                    reloadApp()
                }) { Text(LocalStrings.current.actionReload) }
            },
            dismissButton = {
                TextButton(onClick = { stale = null }) { Text(LocalStrings.current.actionCancel) }
            },
        )
    }
}
