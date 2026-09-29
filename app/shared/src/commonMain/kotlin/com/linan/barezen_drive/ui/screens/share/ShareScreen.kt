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
import androidx.compose.material.icons.filled.CloudOff
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.linan.barezen_drive.data.api.ApiFailure
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.platform.FileSaveRequest
import com.linan.barezen_drive.platform.SaveOutcome
import com.linan.barezen_drive.platform.outcome
import com.linan.barezen_drive.platform.rememberFileSaver
import com.linan.barezen_drive.ui.component.EmptyState
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
    var problem by remember { mutableStateOf<ShareProblem?>(null) }
    var loading by remember { mutableStateOf(true) }
    // Bumped by the retry button, which re-enters the effect below.
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(token, attempt) {
        loading = true
        problem = null
        repo.sharedInfo(token).fold(
            onSuccess = { info = it; loading = false },
            onFailure = { problem = shareProblemFor(it); loading = false },
        )
    }

    val i = info
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        // A link that is alive but unreachable is the common case on a phone
        // that just lost signal. Telling the visitor the link died sends them
        // off to ask the sender for a new one when all they had to do was tap
        // retry.
        problem == ShareProblem.UNREACHABLE -> ShareUnreachable { attempt++ }
        problem != null || i == null -> InvalidShare(onExit)
        i.type == "file" -> SharedFileView(token, i, repo, onExit)
        else -> SharedFolderView(token, i, repo, onExit)
    }
}

/** Why the share entry could not be opened, as far as the client can tell. */
enum class ShareProblem { INVALID, UNREACHABLE }

/**
 * The server answers an unknown, revoked or expired token with 404 and nothing
 * else; a share that was never valid is indistinguishable from one that was
 * pulled. Everything else - no signal, a timeout, a 5xx, an unparseable body -
 * is a link that may well be fine, so it gets a retry instead of a dead end.
 */
fun shareProblemFor(failure: Throwable): ShareProblem =
    if ((failure as? ApiFailure.Http)?.httpStatus in GONE_STATUSES) {
        ShareProblem.INVALID
    } else {
        ShareProblem.UNREACHABLE
    }

private val GONE_STATUSES = setOf(404, 410)

@Composable
private fun ShareUnreachable(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = Icons.Default.CloudOff,
            title = LocalStrings.current.shareLoadFailed,
            actionLabel = LocalStrings.current.actionRetry,
            onAction = onRetry,
            modifier = Modifier.fillMaxSize(),
        )
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
    // remembered, not bare: a fresh SnackbarHostState per recomposition means
    // the host renders a different instance than the one a launched coroutine
    // holds, so showSnackbar's message is never drawn and the call never
    // returns - it suspends waiting for a dismissal that cannot happen.
    val snackbar = remember { SnackbarHostState() }
    // A guest has no transfer centre to look at, so this one keeps the plain
    // saver and reports through the screen's own snackbar. It still has to
    // build a FileSaveRequest now that the request carries the size and the
    // caller's key.
    // Only a genuine write failure is worth a toast here. Dismissing the system
    // save dialog is a decision the user just made, not a broken download.
    val saver = rememberFileSaver(
        onDone = { _, result ->
            if (result.outcome() == SaveOutcome.FAILED) {
                scope.launch { snackbar.showSnackbar(I18n.strings.downloadFailed) }
            }
        },
        onProgress = { _, _ -> },
    )
    val fileId = info.fileId ?: return InvalidShare(onExit)
    // Shared-file download: the guest side has no FileDto, so the request is
    // built from the share info. `size` is what the share endpoint reports, and
    // 0 simply means "unknown" - the platform then reports progress without a
    // percentage.
    fun saveShared() {
        saver(
            FileSaveRequest(
                id = fileId,
                name = info.name.ifBlank { "download" },
                mime = info.mimeType,
                size = info.size ?: 0L,
                open = { repo.sharedDownload(token, fileId) },
            ),
        )
    }
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
                        saveShared()
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
                    saveShared()
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
    // remembered, not bare: a fresh SnackbarHostState per recomposition means
    // the host renders a different instance than the one a launched coroutine
    // holds, so showSnackbar's message is never drawn and the call never
    // returns - it suspends waiting for a dismissal that cannot happen.
    val snackbar = remember { SnackbarHostState() }

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
    val saver = rememberFileSaver(
        onDone = { _, result ->
            if (result.outcome() == SaveOutcome.FAILED) {
                scope.launch { snackbar.showSnackbar(I18n.strings.downloadFailed) }
            }
        },
        onProgress = { _, _ -> },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = {
                saver(
                    FileSaveRequest(file.id, file.name, file.mimeType, file.size) {
                        repo.sharedDownload(token, file.id)
                    },
                )
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
            saver(
                FileSaveRequest(file.id, file.name, file.mimeType, file.size) {
                    repo.sharedDownload(token, file.id)
                },
            )
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
