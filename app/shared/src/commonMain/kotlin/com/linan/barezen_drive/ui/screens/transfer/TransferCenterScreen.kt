package com.linan.barezen_drive.ui.screens.transfer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.linan.barezen_drive.data.transfer.TransferLane
import com.linan.barezen_drive.data.transfer.TransferPhase
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.ui.component.EmptyState
import com.linan.barezen_drive.ui.media.ThumbnailHub
import com.linan.barezen_drive.ui.screens.files.formatFileSize
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha

/**
 * One lane of the transfer centre: album work (sync batches plus manual
 * photo picks) is listed on the album page's screen, file work (uploads and
 * downloads from the files tab) on the file screen reached from home and
 * files. The two never mix, and neither carries sync settings - those live
 * behind the album screen's settings entry, keeping both tabs a plain task
 * list. Two tabs: active work with a live count, finished work beneath it.
 * Sync batches keep their queued children inline while they run; settled
 * children drop to the finished tab so photos complete in view. Every row
 * carries a media tile: the real cover once the server file id exists, a
 * type icon before that.
 *
 * [actions] lets the album lane offer its sync-settings entry in the top
 * bar; the file lane passes nothing.
 */
@Composable
fun TransferCenterScreen(
    lane: TransferLane,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    /** Primary action on the empty state; null hides the button. */
    onGoUpload: (() -> Unit)? = null,
    /** Retry hook for failed rows; null hides the retry affordance. */
    onRetry: ((String) -> Unit)? = null,
) {
    var tab by remember { mutableIntStateOf(0) }
    val all by TransferCenter.items.collectAsState()
    // Batch ids whose stop button was pressed: the worker finishes the
    // in-flight file first, so the row must acknowledge the request.
    var stopRequested by remember { mutableStateOf(setOf<String>()) }

    val mine = all.filter { it.lane == lane }
    val active = mine.filter { it.phase == TransferPhase.RUNNING || it.phase == TransferPhase.QUEUED }
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
    val finished = mine.filter { it.phase != TransferPhase.RUNNING && it.phase != TransferPhase.QUEUED }

    val strings = LocalStrings.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (lane == TransferLane.ALBUM) strings.albumTransfersTitle
                        else strings.fileTransfersTitle,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.actionBack)
                    }
                },
                actions = {
                    // Clearing only makes sense on the finished tab, and it
                    // only touches this lane's rows.
                    if (tab == 1 && finished.isNotEmpty()) {
                        TextButton(onClick = { TransferCenter.clearFinished(lane) }) {
                            Text(strings.clearFinished)
                        }
                    }
                    actions()
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }) {
                    Text(
                        "${strings.tabActive} (${activeRows.size})",
                        Modifier.padding(12.dp),
                    )
                }
                Tab(tab == 1, { tab = 1 }) {
                    Text(
                        "${strings.tabDone} (${finished.size})",
                        Modifier.padding(12.dp),
                    )
                }
            }

            val rows = if (tab == 0) activeRows else finished
            if (rows.isEmpty()) {
                // Empty state gets the same shape as trash/archive: icon, one
                // line, a hint, and - the only screen of the three with an
                // obvious next step - a way to start an upload.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Default.SwapVert,
                        title = if (lane == TransferLane.ALBUM) strings.noAlbumTransfers else strings.noFileTransfers,
                        subtitle = strings.transferEmptyHint,
                        actionLabel = if (onGoUpload != null) strings.actionGoUpload else null,
                        onAction = onGoUpload,
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
                            retryable = onRetry != null && TransferCenter.retryHandle(item.id) != null,
                            onRetry = { onRetry?.invoke(item.id) },
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
    retryable: Boolean = false,
    onRetry: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Batch children tuck under their parent: extra lead padding and a
            // smaller tile read as "part of the batch above" at a glance.
            .padding(start = if (indent) 32.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransferTile(item, size = if (indent) 36 else 44)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Only where a retry can actually run: the source handle is
                    // kept for single-file uploads, not for album batch rows.
                    if (retryable) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onRetry,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Text(
                                LocalStrings.current.actionRetry,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Video file extensions, so the type tile is not tied to a single container. */
private val videoExtensions = setOf("mp4", "mov", "mkv", "webm", "avi", "m4v", "3gp", "ts")

/** Media tile: the real cover when known, a type icon before that. */
@Composable
private fun TransferTile(item: TransferItem, size: Int) {
    val fileId = item.fileId
    var bmp by remember(fileId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(fileId) { bmp = if (fileId != null) ThumbnailHub.load(fileId) else null }
    Box(
        Modifier
            .size(size.dp)
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
                modifier = Modifier.size(size.dp),
            )
        } else {
        val icon = when {
            // The media guess applies before and after the file id exists: the
            // cover fetch may take a moment, and switching the tile from a
            // photo/video glyph to a generic one and back is what read as
            // flicker on in-flight rows.
            item.mediaHint &&
                item.name.substringAfterLast('.', "").lowercase() in videoExtensions -> Icons.Default.Movie
            item.mediaHint -> Icons.Default.Image
            item.parent == null && item.kind == TransferKind.SYNC -> Icons.Default.PhotoLibrary
            else -> Icons.Default.InsertDriveFile
        }
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size((size * 6 / 11).dp),
            )
        }
    }
}
