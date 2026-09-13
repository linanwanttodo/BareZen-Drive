package com.linan.barezen_drive.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.data.transfer.TransferCenter
import com.linan.barezen_drive.data.transfer.TransferKind
import com.linan.barezen_drive.data.transfer.TransferLane
import com.linan.barezen_drive.data.transfer.TransferPhase
import com.linan.barezen_drive.i18n.LocalStrings

/**
 * Top-bar transfer entry, modelled on the Google Photos backup pill: a glyph
 * wrapped in a circle, where the circle doubles as the progress ring. With
 * nothing in flight it is a quiet outlined glyph; while transfers of [lane]
 * run, the ring fills with the aggregate byte progress across that lane's
 * in-flight or queued files (batch children included, batch aggregates
 * excluded) and turns indeterminate only when no size is known yet.
 *
 * The two lanes carry different glyphs on purpose: the album page keeps the
 * upward "backup" arrow, home/files use the up-down double arrow of a
 * netdisk transfer list - so the entry itself says which page it opens.
 */
@Composable
fun TransferEntryIcon(
    lane: TransferLane,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val items by TransferCenter.items.collectAsState()
    val active = items.filter {
        it.lane == lane &&
            (it.phase == TransferPhase.RUNNING || it.phase == TransferPhase.QUEUED) &&
            (it.kind == TransferKind.UPLOAD || it.parent != null)
    }
    val totalBytes = active.filter { it.bytesTotal > 0 }.sumOf { it.bytesTotal }
    val doneBytes = active.filter { it.bytesTotal > 0 }.sumOf { it.bytesDone }
    val known = totalBytes > 0
    IconButton(onClick = onClick) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (active.isNotEmpty()) {
                if (known) {
                    CircularProgressIndicator(
                        progress = { (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 2.dp,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                // Idle: a quiet ring so the glyph never floats unanchored.
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                )
            }
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Album lane entry: the upward backup arrow. */
@Composable
fun AlbumTransferEntryIcon(onClick: () -> Unit) {
    TransferEntryIcon(
        lane = TransferLane.ALBUM,
        icon = Icons.Default.ArrowUpward,
        contentDescription = LocalStrings.current.albumTransfersTitle,
        onClick = onClick,
    )
}

/** File lane entry: the up-down double arrow of a netdisk transfer list. */
@Composable
fun FileTransferEntryIcon(onClick: () -> Unit) {
    TransferEntryIcon(
        lane = TransferLane.FILE,
        icon = Icons.Default.SwapVert,
        contentDescription = LocalStrings.current.fileTransfersTitle,
        onClick = onClick,
    )
}
