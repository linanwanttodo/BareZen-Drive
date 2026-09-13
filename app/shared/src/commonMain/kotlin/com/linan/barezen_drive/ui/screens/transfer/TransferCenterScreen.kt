package com.linan.barezen_drive.ui.screens.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.linan.barezen_drive.platform.MediaSync
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.data.transfer.TransferCenter
import com.linan.barezen_drive.data.transfer.TransferItem
import com.linan.barezen_drive.data.transfer.TransferKind
import com.linan.barezen_drive.data.transfer.TransferPhase
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.platform.BackupPauseReason
import com.linan.barezen_drive.ui.screens.backup.AlbumReviewDialog
import com.linan.barezen_drive.ui.screens.files.formatFileSize

/**
 * Transfer centre: the flow of files in and out, like a netdisk app.
 * Uploading / downloading / finished tabs, plus the album auto-sync switch and
 * its network policy. Replaces the old modal progress popups.
 */
@Composable
fun TransferCenterScreen(
    onBack: () -> Unit,
    autoSync: Boolean,
    onAutoSyncChange: (Boolean) -> Unit,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    chargingOnly: Boolean = false,
    onChargingOnlyChange: (Boolean) -> Unit = {},
    /** True until the user has answered the first-run album review, offered the
     *  moment automatic backup is switched on. */
    offerAlbumReview: Boolean = false,
    onAlbumReviewHandled: () -> Unit = {},
    syncSupported: Boolean = true,
    onSyncNow: () -> Unit = {},
    onOpenBackupAlbums: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    var showReview by remember { mutableStateOf(false) }
    // First-run album review, one prompt with two ways in: flipping the switch on
    // (the user is saying "start backing up", so it is the moment the list gets
    // read) and opening the sync tab with backup already on (covers anyone who
    // enabled it before the review existed). Both require the switch to be on -
    // reviewing albums for backup that is off would be noise. Once answered,
    // [offerAlbumReview] goes false for good and this never fires again.
    LaunchedEffect(tab, autoSync, syncSupported, offerAlbumReview) {
        if (tab == 2 && syncSupported && autoSync && offerAlbumReview) showReview = true
    }
    val backupStatus by MediaSync.status.collectAsState()
    val all by TransferCenter.items.collectAsState()
    // Batch ids whose stop button was pressed: the worker finishes the
    // in-flight file first, so the row must acknowledge the request.
    var stopRequested by remember { mutableStateOf(setOf<String>()) }

    val uploading = all.filter { it.kind == TransferKind.UPLOAD && it.phase == TransferPhase.RUNNING }
    val downloading = all.filter { it.kind == TransferKind.DOWNLOAD && it.phase == TransferPhase.RUNNING }
    // Sync tab: batches with their per-file queue rows beneath them.
    val syncBatches = all.filter { it.kind == TransferKind.SYNC && it.parent == null }
    val syncRows = buildList {
        syncBatches.forEach { batch ->
            add(batch)
            addAll(all.filter { it.parent == batch.id })
        }
    }
    val finished = all.filter { it.phase != TransferPhase.RUNNING && it.parent == null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LocalStrings.current.transfers) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LocalStrings.current.actionBack)
                    }
                },
                actions = {
                    // Clear sits in the top bar: at the bottom of a long
                    // history it was unreachable.
                    TextButton(onClick = { TransferCenter.clearFinished() }) {
                        Text(LocalStrings.current.clearFinished)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }) { Text(LocalStrings.current.tabUploading, Modifier.padding(12.dp)) }
                Tab(tab == 1, { tab = 1 }) { Text(LocalStrings.current.tabDownloading, Modifier.padding(12.dp)) }
                Tab(tab == 2, { tab = 2 }) { Text(LocalStrings.current.tabSync, Modifier.padding(12.dp)) }
                Tab(tab == 3, { tab = 3 }) { Text(LocalStrings.current.tabDone, Modifier.padding(12.dp)) }
            }

            // Album auto-sync settings live here, next to the flows they drive.
            // Hidden where the platform has no background scheduler (web).
            if (tab == 2 && syncSupported) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(LocalStrings.current.albumAutoSync, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                LocalStrings.current.albumAutoSyncHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoSync, onCheckedChange = onAutoSyncChange)
                    }
                    if (autoSync) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(LocalStrings.current.syncWifiOnly, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    LocalStrings.current.syncWifiOnlyHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = wifiOnly, onCheckedChange = onWifiOnlyChange)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(LocalStrings.current.syncChargingOnly, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    LocalStrings.current.syncChargingOnlyHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = chargingOnly, onCheckedChange = onChargingOnlyChange)
                        }
                        // Live backup status: pending / uploading / failed /
                        // excluded counts, the last successful pass, the reason
                        // nothing is running, and the two actions that belong to
                        // it (pick albums, sync now). "Sync now" lives here rather
                        // than on its own row above, so there is exactly one.
                        val startedLabel = LocalStrings.current.syncStarted
                        BackupStatusCard(
                            status = backupStatus,
                            onOpenAlbums = onOpenBackupAlbums,
                            onSyncNow = {
                                onSyncNow()
                                com.linan.barezen_drive.platform.TransferNotifier.toast(startedLabel)
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
            }

            val rows = when (tab) {
                0 -> uploading
                1 -> downloading
                2 -> syncRows
                else -> finished
            }
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        LocalStrings.current.noTransfers,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.id }) { item ->
                        TransferRow(
                            item,
                            indent = item.parent != null,
                            stopping = item.id in stopRequested,
                            onStop = {
                                TransferCenter.cancel(item.id)
                                stopRequested = stopRequested + item.id
                            },
                        )
                    }
                }
            }
        }
    }
    // Outside the Scaffold: a dialog is its own window, so it must not be laid
    // out inside the content column (that would make it scroll with the list).
    // Only a review that actually showed albums counts as answered; a failed
    // scan closes without burning the one-shot flag.
    if (showReview) {
        AlbumReviewDialog(
            onDone = { reviewed ->
                showReview = false
                if (reviewed) onAlbumReviewHandled()
            },
        )
    }
}

@Composable
private fun TransferRow(
    item: TransferItem,
    indent: Boolean = false,
    stopping: Boolean = false,
    onStop: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                when (item.phase) {
                    TransferPhase.DONE -> LocalStrings.current.transferDone
                    TransferPhase.FAILED -> LocalStrings.current.transferFailed
                    TransferPhase.QUEUED -> LocalStrings.current.transferQueued
                    else -> when {
                        // Batch syncs count files, byte uploads count bytes.
                        item.parent == null && item.kind == TransferKind.SYNC && item.bytesTotal > 0 ->
                            "\${item.bytesDone.toInt()} / \${item.bytesTotal.toInt()}"
                        else -> "\${formatFileSize(item.bytesDone)} / \${formatFileSize(item.bytesTotal)}"
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (item.phase == TransferPhase.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Stop button on the batch row: flags the worker, which drops the
            // rest of the queue after the in-flight file.
            if (item.phase == TransferPhase.RUNNING && item.kind == TransferKind.SYNC && item.parent == null) {
                TextButton(onClick = onStop, enabled = !stopping) {
                    Text(if (stopping) LocalStrings.current.stoppingSoon else LocalStrings.current.actionCancel)
                }
            }
        }
        item.statusText?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.phase == TransferPhase.RUNNING) {
            Spacer(Modifier.height(6.dp))
            val fraction = if (item.bytesTotal > 0) {
                (item.bytesDone.toFloat() / item.bytesTotal).coerceIn(0f, 1f)
            } else {
                // Indeterminate when the size is unknown (e.g. instant upload).
                0f
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item.error?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Compact live status of the automatic backup, shown above the sync tab list. */
@Composable
private fun BackupStatusCard(
    status: com.linan.barezen_drive.platform.BackupStatus,
    onOpenAlbums: () -> Unit,
    onSyncNow: () -> Unit,
) {
    val strings = LocalStrings.current
    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(strings.backupCardTitle, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onOpenAlbums) { Text(strings.backupAlbumsTitle) }
            TextButton(onClick = onSyncNow) { Text(strings.backupSyncNow) }
        }
        Text(
            buildString {
                append(strings.backupPending); append(": "); append(status.pending)
                if (status.active > 0) { append("   "); append(strings.backupUploading); append(": "); append(status.active) }
                if (status.failed > 0) { append("   "); append(strings.backupFailed); append(": "); append(status.failed) }
                if (status.excludedBuckets > 0) { append("   "); append(strings.backupExcluded); append(": "); append(status.excludedBuckets) }
            },
            style = MaterialTheme.typography.bodySmall,
        )
        // Why nothing is moving even though the switch is on. Without this the
        // card reads "pending: 300" on an unplugged phone and the user has no way
        // to learn that the charging-only rule they set is what is holding it.
        status.pausedReason?.let { reason ->
            Text(
                when (reason) {
                    BackupPauseReason.SIGNED_OUT -> strings.backupPausedSignedOut
                    BackupPauseReason.NETWORK -> strings.backupPausedNoNetwork
                    BackupPauseReason.CHARGING -> strings.backupPausedCharging
                    BackupPauseReason.BATTERY_LOW -> strings.backupPausedBatteryLow
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        val last = if (status.lastSyncAt > 0L) {
            com.linan.barezen_drive.ui.media.formatDateTime(kotlinx.datetime.Instant.fromEpochMilliseconds(status.lastSyncAt).toString())
        } else {
            strings.backupNever
        }
        Text(
            "${strings.backupLastSync}: $last",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
