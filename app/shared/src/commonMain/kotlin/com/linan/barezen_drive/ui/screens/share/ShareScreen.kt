package com.linan.barezen_drive.ui.screens.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.SharedContentsResponse
import com.linan.barezen_drive.core.dto.SharedFileDto
import com.linan.barezen_drive.core.dto.SharedFolderDto
import com.linan.barezen_drive.core.dto.SharedInfoResponse
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.platform.rememberFileSaver
import com.linan.barezen_drive.ui.screens.files.formatFileSize
import com.linan.barezen_drive.ui.media.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.i18n.LocalStrings
import kotlinx.coroutines.launch

/**
 * Public share landing page (no login required). File shares show metadata and
 * an inline image preview or a direct download; folder shares show a read-only
 * breadcrumb + listing. Invalid/expired links land on a friendly empty state.
 */
@Composable
fun ShareScreen(
    token: String,
    repo: FilesRepository,
    onExit: () -> Unit,
) {
    var info by remember { mutableStateOf<SharedInfoResponse?>(null) }
    var invalid by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(token) {
        repo.sharedInfo(token).fold(
            onSuccess = { info = it; loading = false },
            onFailure = { invalid = true; loading = false },
        )
    }

    val i = info
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        invalid || i == null -> InvalidShare(onExit)
        i.type == "file" -> SharedFileView(token, i, repo, onExit)
        else -> SharedFolderView(token, i, repo, onExit)
    }
}

@Composable
private fun InvalidShare(onExit: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.LinkOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(LocalStrings.current.shareLinkInvalid, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                LocalStrings.current.askSharerForNewLink,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onExit) { Text(LocalStrings.current.goToApp) }
        }
    }
}

// ---- File share ----

@Composable
private fun SharedFileView(
    token: String,
    info: SharedInfoResponse,
    repo: FilesRepository,
    onExit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = SnackbarHostState()
    val saver = rememberFileSaver { result ->
        // A failed or cancelled save must not look like success.
        if (result == null) scope.launch { snackbar.showSnackbar(I18n.strings.downloadFailed) }
    }
    val fileId = info.fileId ?: return InvalidShare(onExit)
    // Only images preview inline; everything else offers a plain download.
    val isImage = info.mimeType?.startsWith("image/") == true

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(info.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LocalStrings.current.actionBack)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        saver(info.name, info.mimeType) { repo.sharedDownload(token, fileId) }
                    }) {
                        Icon(Icons.Default.Download, contentDescription = LocalStrings.current.actionDownload)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    buildString {
                        append(LocalStrings.current.sharedFiles)
                        info.size?.let { append(" · ${formatFileSize(it)}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            if (isImage) {
                SharedImagePreview(token, fileId, repo)
            } else {
                Spacer(Modifier.height(24.dp))
                Text(info.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                info.updatedAt?.let {
                    Text(
                        LocalStrings.current.updatedAt(formatDateTime(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = {
                    saver(info.name, info.mimeType) { repo.sharedDownload(token, fileId) }
                }) { Text(LocalStrings.current.downloadFile) }
            }
        }
    }
}

/** Inline image preview loading through the public content endpoint. */
@Composable
private fun SharedImagePreview(token: String, fileId: String, repo: FilesRepository) {
    var bitmap by remember(token, fileId) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(token, fileId) { mutableStateOf(false) }
    LaunchedEffect(token, fileId) {
        val res = withContext(Dispatchers.Default) {
            runCatching { repo.sharedPreviewBytes(token, fileId).decodeToImageBitmap() }
        }
        res.fold(onSuccess = { bitmap = it }, onFailure = { failed = true })
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            failed -> Text(LocalStrings.current.imageLoadFailed, color = MaterialTheme.colorScheme.error)
            else -> CircularProgressIndicator()
        }
    }
}

// ---- Folder share ----

private data class SharedBreadcrumb(val id: String?, val name: String)

@Composable
private fun SharedFolderView(
    token: String,
    info: SharedInfoResponse,
    repo: FilesRepository,
    onExit: () -> Unit,
) {
    // Breadcrumb from share root; id null = the share root itself.
    var crumbs by remember { mutableStateOf(listOf(SharedBreadcrumb(null, info.name))) }
    val currentId = crumbs.lastOrNull()?.id
    var listing by remember { mutableStateOf<SharedContentsResponse?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val snackbar = SnackbarHostState()

    LaunchedEffect(currentId) {
        repo.sharedContents(token, currentId).fold(
            onSuccess = { listing = it; loadError = null },
            onFailure = { loadError = I18n.strings.loadFailed },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(crumbs.lastOrNull()?.name ?: info.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (crumbs.size > 1) crumbs = crumbs.dropLast(1) else onExit()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LocalStrings.current.actionBack)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            // Breadcrumb row (jump straight to any ancestor).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                crumbs.forEachIndexed { idx, crumb ->
                    if (idx > 0) Text(
                        "/",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        crumb.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (idx == crumbs.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(enabled = idx != crumbs.lastIndex) {
                            crumbs = crumbs.take(idx + 1)
                        },
                    )
                }
            }
            HorizontalDivider()

            val l = listing
            when {
                loadError != null -> CenteredText(loadError ?: LocalStrings.current.loadFailed)
                l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                l.folders.isEmpty() && l.files.isEmpty() -> CenteredText(LocalStrings.current.folderEmpty)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(l.folders, key = { "f_${it.id}" }) { folder ->
                        SharedFolderRow(folder) { crumbs = crumbs + SharedBreadcrumb(folder.id, folder.name) }
                        HorizontalDivider()
                    }
                    items(l.files, key = { "d_${it.id}" }) { file ->
                        SharedFileRow(token, file, repo, snackbar)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedFolderRow(folder: SharedFolderDto, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(folder.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SharedFileRow(
    token: String,
    file: SharedFileDto,
    repo: FilesRepository,
    snackbar: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val saver = rememberFileSaver { result ->
        if (result == null) scope.launch { snackbar.showSnackbar(I18n.strings.downloadFailed) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = {
                saver(file.name, file.mimeType) { repo.sharedDownload(token, file.id) }
            })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatFileSize(file.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = {
            saver(file.name, file.mimeType) { repo.sharedDownload(token, file.id) }
        }) {
            Icon(Icons.Default.Download, contentDescription = LocalStrings.current.actionDownload)
        }
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
