package com.linan.barezen_drive.ui.screens.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.FileDto
import io.ktor.utils.io.ByteReadChannel
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.ui.media.formatDateTime
import com.linan.barezen_drive.ui.screens.files.formatFileSize
import com.linan.barezen_drive.platform.copyToClipboard
import com.linan.barezen_drive.platform.rememberFileSaver
import kotlinx.coroutines.launch

/**
 * Full-screen preview. The pager swipes across the whole collection like a
 * phone gallery; with [showActions] a bottom bar offers download, share, info
 * and delete for the item currently in view (the album screen passes true and
 * refreshes through [onChanged]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    files: List<FileDto>,
    initialIndex: Int,
    repo: FilesRepository,
    onBack: () -> Unit,
    showActions: Boolean = false,
    onChanged: () -> Unit = {},
) {
    var index by remember(initialIndex, files) {
        mutableIntStateOf(initialIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0)))
    }
    val file = files.getOrNull(index) ?: return
    val scope = rememberCoroutineScope()
    val saver = rememberFileSaver { }

    var showInfo by remember { mutableStateOf(false) }
    var shareUrl by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LocalStrings.current.actionBack)
                    }
                },
            )
        },
        bottomBar = {
            if (showActions) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                ) {
                    IconButton(onClick = {
                        saver(file.name, file.mimeType) { repo.download(file.id) }
                    }) {
                        Icon(Icons.Default.Download, contentDescription = LocalStrings.current.actionDownload)
                    }
                    IconButton(onClick = {
                        scope.launch {
                            repo.createShare(fileId = file.id).fold(
                                onSuccess = { shareUrl = it.url },
                                onFailure = { },
                            )
                        }
                    }) {
                        Icon(Icons.Default.Share, contentDescription = LocalStrings.current.actionShare)
                    }
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Default.Info, contentDescription = LocalStrings.current.actionInfo)
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = LocalStrings.current.actionDelete,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            when (PreviewKind.of(file)) {
                PreviewKind.IMAGE -> ImageViewer(
                    files = files,
                    initialIndex = index,
                    repo = repo,
                    onPageChange = { index = it },
                )
                PreviewKind.VIDEO -> PlatformMediaPlayer(file, repo, isAudio = false)
                PreviewKind.AUDIO -> PlatformMediaPlayer(file, repo, isAudio = true)
                PreviewKind.TEXT -> TextViewer(file, repo)
                PreviewKind.PDF -> PlatformPdfViewer(file, repo)
                PreviewKind.OTHER -> Unsupported(file, repo, saver)
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(LocalStrings.current.actionInfo) },
            text = {
                Column {
                    InfoLine(LocalStrings.current.infoName, file.name)
                    InfoLine(LocalStrings.current.infoSize, formatFileSize(file.size))
                    InfoLine(LocalStrings.current.infoModified, formatDateTime(file.updatedAt))
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text(LocalStrings.current.actionClose) }
            },
        )
    }

    shareUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { shareUrl = null },
            title = { Text(LocalStrings.current.actionShare) },
            text = { Text(url) },
            confirmButton = {
                val copiedLabel = LocalStrings.current.copied
                TextButton(onClick = {
                    scope.launch {
                        copyToClipboard(url)
                        shareUrl = null
                    }
                }) { Text(copiedLabel) }
            },
            dismissButton = {
                TextButton(onClick = { shareUrl = null }) { Text(LocalStrings.current.actionCancel) }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(LocalStrings.current.deleteFile) },
            text = { Text(file.name) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repo.deleteFile(file.id).fold(
                            onSuccess = {
                                onChanged()
                                onBack()
                            },
                            onFailure = { },
                        )
                    }
                }) { Text(LocalStrings.current.actionDelete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(LocalStrings.current.actionCancel) }
            },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Unsupported(file: FileDto, repo: FilesRepository, saver: (name: String, mime: String?, open: suspend () -> ByteReadChannel) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(file.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                LocalStrings.current.previewUnsupported,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            // The explicit action replaces the old tap-to-download surprise.
            androidx.compose.material3.Button(
                onClick = { saver(file.name, file.mimeType) { repo.download(file.id) } },
                colors = com.linan.barezen_drive.ui.theme.filledButtonColors(),
            ) {
                Text(LocalStrings.current.actionDownload)
            }
        }
    }
}
