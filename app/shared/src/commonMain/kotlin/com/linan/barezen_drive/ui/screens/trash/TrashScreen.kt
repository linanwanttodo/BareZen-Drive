package com.linan.barezen_drive.ui.screens.trash

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.ui.media.ThumbnailLoader
import com.linan.barezen_drive.ui.media.fileIcon
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha
import kotlinx.coroutines.launch

/**
 * Trash: soft-deleted files that keep their bytes for the retention window.
 * Each row can be restored to the timeline or purged permanently; the header
 * action empties the whole trash after an explicit confirmation. A live file
 * id can never reach here (plain delete is the only thing that lands a row in
 * the trash), and rows past the window are dropped by the server cleanup loop.
 */
@Composable
fun TrashScreen(
    repo: FilesRepository,
    thumbs: ThumbnailLoader,
    onBack: () -> Unit,
    onPreview: (List<FileDto>, Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val strings = LocalStrings.current
    var files by remember { mutableStateOf<List<FileDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<FileDto?>(null) }

    fun load() {
        loading = true
        scope.launch {
            repo.trash().fold(
                onSuccess = { files = it.files; error = null },
                onFailure = { error = it.message?.takeIf { m -> m.isNotBlank() } ?: I18n.strings.loadFailed },
            )
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    fun restore(file: FileDto) {
        scope.launch {
            repo.restoreFromTrash(file.id).fold(
                onSuccess = { files = files.filterNot { f -> f.id == file.id }; snackbar.showSnackbar(strings.restored) },
                onFailure = { snackbar.showSnackbar(I18n.strings.operationFailed) },
            )
        }
    }
    fun purge(file: FileDto) {
        scope.launch {
            repo.deleteForever(file.id).fold(
                onSuccess = { files = files.filterNot { f -> f.id == file.id }; snackbar.showSnackbar(strings.deletedForever) },
                onFailure = { snackbar.showSnackbar(I18n.strings.operationFailed) },
            )
        }
    }
    fun empty() {
        scope.launch {
            repo.emptyTrash().fold(
                onSuccess = { files = emptyList(); snackbar.showSnackbar(strings.trashEmptied) },
                onFailure = { snackbar.showSnackbar(I18n.strings.operationFailed) },
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(strings.trashTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.actionBack)
                    }
                },
                actions = {
                    if (files.isNotEmpty()) {
                        IconButton(onClick = { confirmEmpty = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = strings.actionEmptyTrash)
                        }
                    }
                },
            )
        },
    ) { pad ->
        when {
            loading && files.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && files.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: strings.loadFailed, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { error = null; load() }) { Text(strings.actionRetry) }
                }
            }
            files.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(strings.trashEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(strings.autoCleanupHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier.fillMaxSize().padding(pad),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(12.dp),
            ) {
                items(files, key = { it.id }) { file ->
                    val index = files.indexOfFirst { f -> f.id == file.id }.coerceAtLeast(0)
                    TrashCard(
                        file = file,
                        thumbs = thumbs,
                        onOpen = { onPreview(files, index) },
                        onRestore = { restore(file) },
                        onPurge = { confirmPurge = file },
                    )
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text(strings.actionEmptyTrash) },
            text = { Text(strings.confirmEmptyTrash) },
            confirmButton = {
                TextButton(onClick = { confirmEmpty = false; empty() }) { Text(strings.actionConfirm) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text(strings.actionCancel) } },
        )
    }
    confirmPurge?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmPurge = null },
            title = { Text(strings.actionDeleteForever) },
            text = { Text(strings.confirmDeleteForever) },
            confirmButton = {
                TextButton(onClick = { confirmPurge = null; purge(target) }) { Text(strings.actionConfirm) }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = null }) { Text(strings.actionCancel) } },
        )
    }
}

@Composable
private fun TrashCard(
    file: FileDto,
    thumbs: ThumbnailLoader,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        CoverThumbnail(file, thumbs, Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onOpen))
        Text(
            file.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRestore) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text(LocalStrings.current.actionRestore)
            }
            TextButton(onClick = onPurge) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(4.dp))
                Text(LocalStrings.current.actionDeleteForever, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CoverThumbnail(file: FileDto, thumbs: ThumbnailLoader, modifier: Modifier = Modifier) {
    var bitmap by remember(file.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.id) { bitmap = thumbs.load(file.id) }
    val bmp = bitmap
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            Image(bitmap = bmp, contentDescription = file.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(fileIcon(file.mimeType), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
