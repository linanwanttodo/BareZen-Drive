package com.linan.barezen_drive.ui.screens.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.platform.BackupBucket
import com.linan.barezen_drive.platform.BackupPauseReason
import com.linan.barezen_drive.platform.MediaSync

/**
 * Album backup hub - the single home of everything album-sync related, kept
 * out of the transfer centre on purpose: that page lists every transfer of
 * every kind, and a backup control block on top of it kept squatting the
 * screen. Settings mirror the old transfer-centre card (master switch, WiFi,
 * charging, live counters, pause reason, last sync), then the per-album list
 * where each album carries its own inclusion toggle.
 */
@Composable
fun SyncAlbumsScreen(
    onBack: () -> Unit,
    autoSync: Boolean,
    onAutoSyncChange: (Boolean) -> Unit,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    chargingOnly: Boolean = false,
    onChargingOnlyChange: (Boolean) -> Unit = {},
    /** True until the user has answered the first-run album review, offered
     *  the moment automatic backup is switched on. */
    offerAlbumReview: Boolean = false,
    onAlbumReviewHandled: () -> Unit = {},
    onSyncNow: () -> Unit,
    onOpenTransfers: () -> Unit = {},
) {
    val strings = LocalStrings.current
    var buckets by remember { mutableStateOf<List<BackupBucket>?>(null) }
    // Local mirror of the inclusion flags. The list is read exactly once here:
    // MediaSync.listBuckets() runs a full MediaStore scan, so re-reading it after
    // every checkbox - the previous behaviour - cost one complete gallery pass per
    // tap on the one screen that exists to be tapped through. Writes still go
    // straight through to the queue, which is what actually persists the choice.
    var included by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    // null buckets means "still loading"; this flag separates "the scan failed"
    // (denied media permission) from "the scan worked and found nothing", which
    // need opposite treatments: one is a retry, the other is genuinely empty.
    var loadFailed by remember { mutableStateOf(false) }
    // First-run album review: flipping the switch on is the moment the list
    // gets read. Once answered, [offerAlbumReview] goes false for good.
    var showReview by remember { mutableStateOf(false) }

    LaunchedEffect(autoSync, offerAlbumReview) {
        if (autoSync && offerAlbumReview) showReview = true
    }
    val backupStatus by MediaSync.status.collectAsState()

    LaunchedEffect(Unit) {
        loadFailed = false
        buckets = runCatching { MediaSync.listBuckets() }
            .onFailure { loadFailed = true }
            .getOrNull()
        buckets?.let { loaded -> included = loaded.associate { it.name to it.included } }
    }

    fun setIncluded(name: String, on: Boolean) {
        included = included + (name to on)
        MediaSync.setBucketIncluded(name, on)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.backupAlbumsTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.actionBack)
                    }
                },
                actions = {
                    // The plain transfer queue: uploads and downloads of every
                    // kind, no backup controls mixed in.
                    TextButton(onClick = onOpenTransfers) { Text(strings.transfers) }
                    // Bulk restore: the mirror is replaced in one assignment (not
                    // per row) so the list recomposes once, and the queue re-drive
                    // collapses into a single enqueue server-side (unique work
                    // with KEEP) no matter how many albums are restored.
                    TextButton(
                        onClick = {
                            val loaded = buckets ?: return@TextButton
                            included = loaded.associate { it.name to true }
                            loaded.forEach { MediaSync.setBucketIncluded(it.name, true) }
                        },
                        enabled = !buckets.isNullOrEmpty(),
                    ) { Text(strings.backupAlbumsReviewAll) }
                    TextButton(onClick = onSyncNow) { Text(strings.backupSyncNow) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Backup settings, mirroring the switch card the transfer centre
            // used to carry: one master switch plus its conditions, and the
            // live queue numbers so "why is nothing moving" answers itself.
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
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
                // One static summary line instead of three jittering counters:
                // every number in one place, growing only when it changes.
                Text(
                    buildString {
                        append(strings.backupPending); append(" "); append(backupStatus.pending)
                        if (backupStatus.failed > 0) {
                            append("   "); append(strings.backupFailed); append(" "); append(backupStatus.failed)
                        }
                        if (backupStatus.active > 0) {
                            append("   "); append(strings.backupUploading); append(" "); append(backupStatus.active)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                backupStatus.pausedReason?.let { reason ->
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
                val last = if (backupStatus.lastSyncAt > 0L) {
                    com.linan.barezen_drive.ui.media.formatDateTime(kotlinx.datetime.Instant.fromEpochMilliseconds(backupStatus.lastSyncAt).toString())
                } else {
                    strings.backupNever
                }
                Text(
                    "${strings.backupLastSync}: $last",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            HorizontalDivider()
            Text(
                strings.backupAlbumsHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                loadFailed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(strings.loadFailed, color = MaterialTheme.colorScheme.error)
                }
                buckets == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                buckets!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(strings.backupAlbumsEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn {
                    items(buckets!!, key = { it.name }) { bucket ->
                        val on = included[bucket.name] ?: bucket.included
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { setIncluded(bucket.name, !on) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = { checked -> setIncluded(bucket.name, checked) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(bucket.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    buildString {
                                        append(bucket.total)
                                        append(' ')
                                        append(strings.backupAlbumItem)
                                        append(" · ")
                                        append(if (on) strings.backupOn else strings.backupOff)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    // Excluded albums are called out in colour: the
                                    // checkbox alone is easy to miss on a long list.
                                    color = if (on) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
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
