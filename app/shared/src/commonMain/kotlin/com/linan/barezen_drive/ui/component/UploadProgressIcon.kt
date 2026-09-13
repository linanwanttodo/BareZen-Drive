package com.linan.barezen_drive.ui.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.data.transfer.TransferCenter
import com.linan.barezen_drive.data.transfer.TransferKind
import com.linan.barezen_drive.data.transfer.TransferPhase
import com.linan.barezen_drive.i18n.LocalStrings

/**
 * Top-bar upload entry, modelled on the Google Photos backup pill: a single
 * upward arrow wrapped in a circle, where the circle doubles as the progress
 * ring. With nothing in flight it is a quiet outlined arrow; while uploads run
 * the ring fills with the aggregate byte progress across every in-flight or
 * queued file (batch children included, batch aggregates excluded) and turns
 * indeterminate only when no size is known yet.
 */
@Composable
fun UploadProgressIcon(onClick: () -> Unit) {
    val items by TransferCenter.items.collectAsState()
    val active = items.filter {
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
                // Idle: a quiet ring so the arrow never floats unanchored.
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                )
            }
            Icon(
                Icons.Default.ArrowUpward,
                contentDescription = LocalStrings.current.transfers,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
