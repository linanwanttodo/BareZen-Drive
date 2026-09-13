package com.linan.barezen_drive.ui.screens.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.data.transfer.TransferCenter
import com.linan.barezen_drive.data.transfer.TransferItem
import com.linan.barezen_drive.data.transfer.TransferKind
import com.linan.barezen_drive.data.transfer.TransferPhase
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.ui.media.ThumbnailHub
import com.linan.barezen_drive.ui.screens.files.formatFileSize

/**
 * Transfer centre: everything that flows in and out of the device on one page,
 * and nothing else - album backup lives on its own screen, so this stays a
 * plain task list no matter what kind of work the rows represent. Two tabs:
 * active work with a live count in the tab, finished work beneath it. Sync
 * batches live in the active tab while they run; their per-file rows split by
 * phase, so finished photos are visible without leaving the page. Every row
 * carries a media tile: the real cover once the server file id exists, a type
 * icon before that.
 */
@Composable
fun TransferCenterScreen(
    onBack: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
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
