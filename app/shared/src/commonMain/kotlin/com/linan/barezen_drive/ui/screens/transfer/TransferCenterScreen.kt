package com.linan.barezen_drive.ui.screens.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.data.transfer.TransferCenter
import com.linan.barezen_drive.data.transfer.TransferItem
import com.linan.barezen_drive.data.transfer.TransferKind
import com.linan.barezen_drive.data.transfer.TransferPhase
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.platform.BackupPauseReason
import com.linan.barezen_drive.ui.media.ThumbnailHub
import com.linan.barezen_drive.ui.screens.backup.AlbumReviewDialog
import com.linan.barezen_drive.ui.screens.files.formatFileSize

/**
 * Transfer centre: everything that flows in and out of the device on one page.
 *
 * Layout, after the 0.0.x redesign: the album-backup card sits at the top and
 * is always there while the platform supports it (the switches used to hide
 * behind the sync tab, which read like "the options vanished"), then two tabs
 * - active work with a live count in the tab, finished work beneath it. Sync
 * batches live in the active tab while they run; their per-file rows split by
 * phase, so finished photos are visible without leaving the page. Every row
 * carries a media tile: the real cover once the server file id exists, a type
 * icon before that.
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
    // First-run album review: flipping the switch on is the moment the list
    // gets read. Once answered, [offerAlbumReview] goes false for good.
    LaunchedEffect(autoSync, syncSupported, offerAlbumReview) {
        if (syncSupported && autoSync && offerAlbumReview) showReview = true
    }
    val backupStatus by MediaSync.status.collectAsState()
    val all by TransferCenter.items.collectAsState()
    // Batch ids whose stop button was pressed: the worker finishes the
    // in-flight file first, so the row must acknowledge the request.
    var stopRequested by remember { mutableStateOf(setOf<String>()) }

    val active = all.filter { it.phase == TransferPhase.RUNNING || it.phase == TransferPhase.QUEUED }
    // Active batches keep their queued children inline; settled children drop
    // to the finished tab so the user sees photos complete without switching.
    val activeRows = buildList {
        active.forEach { item ->
            add(item)
            if (item.parent == null && item.kind == TransferKind.SYNC) {
                addAll(active.filter { it.parent == item.id })
            }
        }
    }
    val finished = all.filter { it.phase != TransferPhase.RUNNING && it.phase != TransferPhase.QUEUED }

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
                    TextButton(onClick = { TransferCenter.clearFinished() }) {
                        Text(LocalStrings.current.clearFinished)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // The backup card is a fixture of the page, not a tab feature.
            if (syncSupported) {
                BackupSettingsCard(
                    status = backupStatus,
                    autoSync = autoSync,
                    onAutoSyncChange = { on ->
                        onAutoSyncChange(on)
                        // Turning the switch off is also "stop what you are
                        // doing": the schedule goes away and a running pass
                        // winds down after the in-flight file.
                        if (!on) MediaSync.stop()
                    },
                    wifiOnly = wifiOnly,
                    onWifiOnlyChange = onWifiOnlyChange,
                    chargingOnly = chargingOnly,
                    onChargingOnlyChange = onChargingOnlyChange,
                    onOpenAlbums = onOpenBackupAlbums,
                    onSyncNow = onSyncNow,
                    onStop = { MediaSync.stop() },
                )
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
            }

            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }) {
                    Text(
                        "${LocalStrings.current.tabActive} (${activeRows.size})",
                        Modifier.padding(12.dp),
                    )
                }
                Tab(tab == 1, { tab = 1 }) {
                    Text(
                        "${LocalStrings.current.tabDone} (${finished.size})",
                        Modifier.padding(12.dp),
                    )
                }
            }

            val rows = if (tab == 0) activeRows else finished
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
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    // Outside the Scaffold: a dialog is its own window, so it must not be laid
    // out inside the content column. Only a review that actually showed albums
    // counts as answered; a failed scan closes without burning the one-shot.
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
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 16.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransferTile(item)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
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
                                "${item.bytesDone.toInt()} / ${item.bytesTotal.toInt()}"
                            else -> "${formatFileSize(item.bytesDone)} / ${formatFileSize(item.bytesTotal)}"
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
}

/** 44 dp media tile: the real cover when known, a type icon before that. */
@Composable
private fun TransferTile(item: TransferItem) {
    val fileId = item.fileId
    var bmp by remember(fileId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(fileId) { bmp = if (fileId != null) ThumbnailHub.load(fileId) else null }
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bmp
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp),
            )
        } else {
            val icon = when {
                fileId == null && item.mediaHint && item.name.endsWith(".mp4", true) -> Icons.Default.Movie
                fileId == null && item.mediaHint -> Icons.Default.Image
                fileId == null && item.parent == null && item.kind == TransferKind.SYNC -> Icons.Default.PhotoLibrary
                else -> Icons.Default.InsertDriveFile
            }
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Album backup entry, collapsed to one compact row so the transfer list stays
 * the main body of the page: the transfers here are not only album work, and a
 * settings panel used to squat the whole top of the screen. Expanded on tap -
 * switches, counters, pause reasons and actions live one tap below. The live
 * numbers and the stop button stay visible even while collapsed.
 */
@Composable
private fun BackupSettingsCard(
    status: com.linan.barezen_drive.platform.BackupStatus,
    autoSync: Boolean,
    onAutoSyncChange: (Boolean) -> Unit,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    chargingOnly: Boolean,
    onChargingOnlyChange: (Boolean) -> Unit,
    onOpenAlbums: () -> Unit,
    onSyncNow: () -> Unit,
    onStop: () -> Unit,
) {
    val strings = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "backup-chevron")

    Column(Modifier.fillMaxWidth()) {
        // Collapsed header: title, live counters, stop, and the expander.
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CloudUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(strings.backupCardTitle, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append(strings.backupPending); append(" "); append(status.pending)
                        if (status.failed > 0) {
                            append("   "); append(strings.backupFailed); append(" "); append(status.failed)
                        }
                        if (status.active > 0) {
                            append("   "); append(strings.backupUploading); append(" "); append(status.active)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status.active > 0) {
                TextButton(onClick = onStop) { Text(strings.stopSync) }
            }
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = strings.showBackupSettings,
                modifier = Modifier.graphicsLayer { rotationZ = chevron },
            )
        }

        // Expanded panel: the full settings, exactly as before.
        if (expanded) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(strings.albumAutoSync, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            strings.albumAutoSyncHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoSync, onCheckedChange = onAutoSyncChange)
                }
                if (autoSync) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(strings.syncWifiOnly, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                strings.syncWifiOnlyHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = wifiOnly, onCheckedChange = onWifiOnlyChange)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(strings.syncChargingOnly, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                strings.syncChargingOnlyHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = chargingOnly, onCheckedChange = onChargingOnlyChange)
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onOpenAlbums) { Text(strings.backupAlbumsTitle) }
                    TextButton(onClick = onSyncNow) { Text(strings.backupSyncNow) }
                }
                // The failed count is a subset of the pending one, not work that
                // vanished from it: every failed photo still waits to back up and
                // retries by itself, so the two numbers always add up honestly.
                if (status.failed > 0) {
                    Text(
                        "${strings.backupFailed}: ${status.failed} (${strings.backupFailedRetryHint})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                // Why nothing is moving even though the switch is on.
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
    }
}
