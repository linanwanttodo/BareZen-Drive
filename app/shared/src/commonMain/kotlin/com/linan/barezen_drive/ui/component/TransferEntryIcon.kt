package com.linan.barezen_drive.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.linan.barezen_drive.data.transfer.TransferCenter
import com.linan.barezen_drive.data.transfer.TransferKind
import com.linan.barezen_drive.data.transfer.TransferPhase
import com.linan.barezen_drive.i18n.LocalStrings

/**
 * Shared transfer-centre entry for every top bar (home, files, album).
 * One badge rule for the whole app: the count is the number of uploads
 * in flight or queued (batch children included, batch aggregates
 * excluded), collected live from the transfer centre state. Without an
 * active upload the plain icon shows, matching a netdisk app.
 */
@Composable
fun TransferEntryIcon(onClick: () -> Unit) {
    val items by TransferCenter.items.collectAsState()
    val activeUploads = items.count {
        (it.phase == TransferPhase.RUNNING || it.phase == TransferPhase.QUEUED) &&
            (it.kind == TransferKind.UPLOAD || it.parent != null)
    }
    IconButton(onClick = onClick) {
        BadgedBox(badge = {
            if (activeUploads > 0) {
                Badge { Text("$activeUploads") }
            }
        }) {
            Icon(Icons.Default.SwapVert, contentDescription = LocalStrings.current.transfers)
        }
    }
}
