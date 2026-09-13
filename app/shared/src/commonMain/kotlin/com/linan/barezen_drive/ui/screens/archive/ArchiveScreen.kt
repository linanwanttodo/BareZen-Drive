package com.linan.barezen_drive.ui.screens.archive

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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Unarchive
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
 * Archived photos: hidden from the timeline and search until unarchived.
 * Unlike the trash these rows keep living as normal files, so this screen only
 * offers "unarchive" plus the normal full-screen preview. A single page of up
 * to [ARCHIVE_PAGE] covers the realistic archive size without paging plumbing.
 */
private const val ARCHIVE_PAGE = 500

@Composable
fun ArchiveScreen(
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

    fun load() {
        loading = true
        scope.launch {
            repo.album(limit = ARCHIVE_PAGE, archived = true).fold(
                onSuccess = { files = it.files; error = null },
                onFailure = { error = it.message?.takeIf { m -> m.isNotBlank() } ?: I18n.strings.loadFailed },
            )
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    fun unarchive(file: FileDto) {
        scope.launch {
            repo.setArchived(file.id, false).fold(
                onSuccess = { files = files.filterNot { f -> f.id == file.id }; snackbar.showSnackbar(strings.actionUnarchive) },
                onFailure = { snackbar.showSnackbar(I18n.strings.operationFailed) },
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(strings.archiveTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.actionBack)
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
                Text(strings.archiveEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier.fillMaxSize().padding(pad),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(12.dp),
            ) {
                itemsIndexed(files, key = { _, f -> f.id }) { i, file ->
                    ArchiveCard(
                        file = file,
                        thumbs = thumbs,
                        onOpen = { onPreview(files, i) },
                        onUnarchive = { unarchive(file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchiveCard(
    file: FileDto,
    thumbs: ThumbnailLoader,
    onOpen: () -> Unit,
    onUnarchive: () -> Unit,
) {
    val strings = LocalStrings.current
    var bitmap by remember(file.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.id) { bitmap = thumbs.load(file.id) }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                Image(bitmap = bmp, contentDescription = file.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(fileIcon(file.mimeType), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            file.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onUnarchive) {
                Icon(Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text(strings.actionUnarchive)
            }
        }
    }
}
