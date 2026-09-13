package com.linan.barezen_drive.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.linan.barezen_drive.data.update.UpdateBadge
import com.linan.barezen_drive.data.update.UpdateStatus
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.platform.downloadAndInstallUpdate
import com.linan.barezen_drive.platform.installChannel
import com.linan.barezen_drive.platform.InstallChannel
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
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf<UpdateStatus?>(null) }
    val scope = rememberCoroutineScope()
    val badgeAvailable by UpdateBadge.available.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy && !downloading) {
                busy = true
                scope.launch {
                    val outcome = runCatching { checkUpdate() }.getOrDefault(UpdateStatus.Failed)
                    status = outcome
                    UpdateBadge.set(outcome is UpdateStatus.Available)
                    busy = false
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(LocalStrings.current.checkForUpdates, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (downloading) {
                    LocalStrings.current.updateDownloading
                } else {
                    LocalStrings.current.currentVersion(currentVersion)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (downloading) {
            // Live download progress; indeterminate only before the first byte.
            if (downloadProgress > 0f) {
                Text(
                    "${(downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (badgeAvailable) {
            // Red dot mirrors the settings-tab badge until handled.
            Box(
                Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
            )
        }
    }

    status?.let { outcome ->
        UpdateResultDialog(outcome = outcome, onDismiss = { status = null }, onDownload = {
            downloading = true
            downloadProgress = 0f
            scope.launch {
                val url = (outcome as? UpdateStatus.Available)
                val target = url?.downloadUrl ?: return@launch
                // Wire the platform progress callback into the row: without it
                // the percentage label never moves off zero.
                val ok = runCatching {
                    downloadAndInstallUpdate(target) { p -> downloadProgress = p }
                }.getOrDefault(false)
                downloading = false
                if (ok) {
                    UpdateBadge.set(false)
                } else {
                    openInBrowser(url.releaseUrl ?: target)
                }
            }
        })
    }
}

@Composable
private fun UpdateResultDialog(
    outcome: UpdateStatus,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
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
                    when {
                        outcome.reloadOnly -> LocalStrings.current.webUpdateViaServer(outcome.version)
                        outcome.downloadUrl != null -> LocalStrings.current.downloadUpdateHint(outcome.version)
                        else -> LocalStrings.current.updateAvailableVersion(outcome.version)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    when {
                        outcome.reloadOnly -> reloadApp()
                        // The manifest carries this platform's package: download
                        // in place and hand it to the installer. Failure falls
                        // back to the release page.
                        outcome.downloadUrl != null && installChannel == InstallChannel.ANDROID -> onDownload()
                        outcome.downloadUrl != null -> openInBrowser(outcome.downloadUrl)
                        else -> outcome.releaseUrl?.let(::openInBrowser)
                    }
                }) {
                    Text(
                        when {
                            outcome.reloadOnly -> LocalStrings.current.actionReload
                            outcome.downloadUrl != null -> LocalStrings.current.actionDownloadUpdate
                            else -> LocalStrings.current.openReleasePage
                        },
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
