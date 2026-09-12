package com.linan.barezen_drive.ui.screens.files

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.FolderDto
import com.linan.barezen_drive.core.dto.ShareDto
import com.linan.barezen_drive.platform.copyToClipboard
import com.linan.barezen_drive.data.local.AppPreferences
import com.linan.barezen_drive.data.repo.AlbumFolder
import com.linan.barezen_drive.data.repo.AuthRepository
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.data.upload.UploadManager
import com.linan.barezen_drive.platform.PickedFile
import com.linan.barezen_drive.platform.rememberFilePicker
import com.linan.barezen_drive.platform.rememberFileSaver
import com.linan.barezen_drive.ui.media.FileThumbnail
import com.linan.barezen_drive.ui.media.ThumbnailLoader
import com.linan.barezen_drive.ui.media.formatDateTime
import com.linan.barezen_drive.ui.screens.preview.PreviewKind
import kotlinx.coroutines.launch
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.i18n.LocalStrings

private const val ROOT_FOLDER_ID = "root"

private data class ContentsUi(val folders: List<FolderDto>, val files: List<FileDto>)

private data class MoveTarget(val fileId: String, val fromFolderId: String?, val fileName: String)

// Locale-free numeric formatting so the code stays platform-agnostic.
private fun formatOneDecimal(value: Double): String {
    val scaled = (value * 10.0).toInt()
    return "${scaled / 10}.${scaled % 10}"
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${formatOneDecimal(bytes / 1024.0)} KB"
    bytes < 1024L * 1024L * 1024L -> "${formatOneDecimal(bytes / 1048576.0)} MB"
    else -> {
        val scaled = ((bytes / 1073741824.0) * 100.0).toInt()
        "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')} GB"
    }
}

/**
 * Shared open behavior for one file row or tile: EVERY kind opens the preview
 * screen (images get the current listing as swipe context). Types without a
 * built-in renderer (zip, epub...) show file info there with an explicit
 * download button - tapping a file must never download silently.
 */
internal fun openOrPreview(
    file: FileDto,
    listing: List<FileDto>,
    onPreview: (List<FileDto>, Int) -> Unit,
    repo: FilesRepository,
) {
    when (PreviewKind.of(file)) {
        PreviewKind.IMAGE -> {
            val images = listing.filter { PreviewKind.of(it) == PreviewKind.IMAGE }
            val index = images.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
            onPreview(images, index)
        }
        else -> onPreview(listOf(file), 0)
    }
}

@Composable
fun FilesScreen(
    path: List<FolderDto>,
    repo: FilesRepository,
    uploader: UploadManager,
    auth: AuthRepository,
    thumbs: ThumbnailLoader,
    wallpaperBehind: Boolean = false,
    onOpenFolder: (FolderDto) -> Unit,
    onOpenTransfers: () -> Unit = {},
    avatar: @Composable () -> Unit = {},
    onJumpTo: (Int) -> Unit,
    onPreview: (List<FileDto>, Int) -> Unit,
    onLoggedOut: () -> Unit,
    themeToggle: (@Composable () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val snackbar = SnackbarHostState()
    val currentPath by rememberUpdatedState(path)
    val folderId = path.lastOrNull()?.id ?: ROOT_FOLDER_ID

    var state by remember(path) { mutableStateOf<ContentsUi?>(null) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var pendingUploads by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var uploading by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Any?>(null) }
    var renameTarget by remember { mutableStateOf<Triple<Boolean, String, String>?>(null) }
    var moveTarget by remember { mutableStateOf<MoveTarget?>(null) }
    var deleting by remember { mutableStateOf<Any?>(null) }
    // Share dialog target: folder or file DTO (menu "share" opens it).
    var shareTarget by remember { mutableStateOf<Any?>(null) }
    // View mode comes from the persisted preference; the switcher in the top
    // app bar writes straight back to AppPreferences so it survives restarts.
    var gridView by remember { mutableStateOf(AppPreferences.get().filesViewMode == 1) }
    fun setGridView(grid: Boolean) {
        gridView = grid
        AppPreferences.get().filesViewMode = if (grid) 1 else 0
    }

    fun msg(e: Throwable, fallback: String) = e.message?.takeIf { it.isNotBlank() } ?: fallback

    fun reload() {
        scope.launch {
            repo.contents(folderId).fold(
                onSuccess = {
                    // At the root level the dedicated album folder is pinned first.
                    val folders = if (folderId == ROOT_FOLDER_ID) {
                        it.folders.sortedByDescending { f -> f.name == AlbumFolder.ROOT_NAME }
                    } else it.folders
                    state = ContentsUi(folders, it.files)
                    loadError = null
                },
                onFailure = { loadError = msg(it, I18n.strings.loadFailed); snackbar.showSnackbar(loadError ?: I18n.strings.loadFailed) },
            )
        }
    }

    val picker = rememberFilePicker { picks ->
        if (picks.isNotEmpty()) pendingUploads = picks
    }
    val saver = rememberFileSaver { ok ->
        if (ok == null) scope.launch { snackbar.showSnackbar(I18n.strings.downloadFailed) }
    }

    // Upload flow: pick the destination FIRST, then the files. The chosen
    // folder id feeds the pending-upload drain via rememberUpdatedState-like
    // state read (null = the folder currently on screen).
    var showUploadLocation by remember { mutableStateOf(false) }
    var uploadTarget by remember { mutableStateOf<Pair<String?, String>?>(null) } // id to display name
    // Long-press multi-select (Google Photos style): entering selection turns
    // the row's 3-dot into a circle checkbox and swaps the top bar for an
    // action bar with select-all.
    val selectedFiles = remember { mutableStateOf(setOf<String>()) }

    // Drain pending picks sequentially; the target folder is read through
    // rememberUpdatedState so a stale picker closure cannot upload into a
    // folder the user has already navigated away from.
    LaunchedEffect(pendingUploads) {
        val picks = pendingUploads
        if (picks.isEmpty() || uploading) return@LaunchedEffect
        uploading = true
        val target = uploadTarget?.first ?: currentPath.lastOrNull()?.id
        try {
            for (p in picks) {
                uploader.upload(p, target).fold(
                    onSuccess = { },
                    onFailure = { e -> snackbar.showSnackbar(msg(e, I18n.strings.uploadFailedNamed(p.name))) },
                )
            }
        } finally {
            pendingUploads = emptyList()
            uploading = false
            uploadTarget = null
            reload()
        }
    }

    // A fresh folder clears the selection - it belongs to one listing.
    LaunchedEffect(path) {
        selectedFiles.value = emptySet()
        reload()
    }

    val progress by uploader.progress.collectAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
        topBar = {
            Column {
                TopAppBar(
                    colors = if (wallpaperBehind) {
                        TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    } else {
                        TopAppBarDefaults.topAppBarColors()
                    },
                    title = { Text(LocalStrings.current.tabFiles) },
                    actions = {
                        // Upload destination, new folder and transfers live in
                        // the top bar; the breadcrumb moves below it.
                        IconButton(onClick = { showUploadLocation = true }, enabled = !uploading) {
                            Icon(Icons.Default.Upload, contentDescription = LocalStrings.current.actionUpload)
                        }
                        IconButton(onClick = { showNewFolder = true }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = LocalStrings.current.newFolder)
                        }
                        IconButton(onClick = onOpenTransfers) {
                            Icon(Icons.Default.SwapVert, contentDescription = LocalStrings.current.transfers)
                        }
                        themeToggle?.invoke()
                        IconButton(onClick = { setGridView(!gridView) }) {
                            Icon(
                                if (gridView) Icons.Default.ViewList else Icons.Default.GridView,
                                contentDescription = if (gridView) LocalStrings.current.viewList
                                else LocalStrings.current.viewGrid,
                            )
                        }
                        avatar()
                    },
                )
                HorizontalDivider()
                // Breadcrumb: below the bar, same text size as the file rows.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onJumpTo(-1) }) {
                        Text(LocalStrings.current.rootFolder, style = MaterialTheme.typography.bodyLarge)
                    }
                    path.forEachIndexed { i, f ->
                        Text(" / ", style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { onJumpTo(i) }) {
                            Text(
                                f.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        },
    ) { pad ->
        val ui = state
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            val sel = selectedFiles.value
            if (sel.isNotEmpty() && ui != null) {
                // Selection action bar: count, select all, then bulk actions.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        selectedFiles.value = ui.files.map { it.id }.toSet()
                    }) { Text(LocalStrings.current.selectAll) }
                    Text(
                        "${sel.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        sel.forEach { id ->
                            ui.files.firstOrNull { it.id == id }?.let {
                                saver(it.name, it.mimeType) { repo.download(it.id) }
                            }
                        }
                        selectedFiles.value = emptySet()
                    }) { Icon(Icons.Default.Download, contentDescription = LocalStrings.current.actionDownload) }
                    IconButton(onClick = {
                        shareTarget = ui.files.firstOrNull { it.id == sel.first() }
                        selectedFiles.value = emptySet()
                    }) { Icon(Icons.Default.Share, contentDescription = LocalStrings.current.actionShare) }
                    IconButton(onClick = {
                        deleting = ui.files.firstOrNull { it.id == sel.first() }
                        selectedFiles.value = emptySet()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = LocalStrings.current.actionDelete,
                            tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = { selectedFiles.value = emptySet() }) {
                        Icon(Icons.Default.Close, contentDescription = LocalStrings.current.actionCancel)
                    }
                }
                HorizontalDivider()
            }
            val p = progress
            if (p.phase != UploadManager.Phase.IDLE && p.phase != UploadManager.Phase.DONE) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        when (p.phase) {
                            UploadManager.Phase.HASHING -> LocalStrings.current.verifyingFile(p.fileName)
                            UploadManager.Phase.UPLOADING -> LocalStrings.current.uploading(p.fileName)
                            UploadManager.Phase.COMPLETING -> LocalStrings.current.finalizingFile(p.fileName)
                            else -> LocalStrings.current.uploadFailedNamed(p.fileName)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { p.fraction.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    if (p.phase == UploadManager.Phase.FAILED) {
                        Text(
                            p.error?.message ?: LocalStrings.current.uploadFailedRetry,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            when {
                ui == null && loadError != null -> {
                    // Load failed: show the error with a retry affordance instead of an
                    // endless spinner.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                loadError ?: LocalStrings.current.loadFailed,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { loadError = null; reload() }) { Text(LocalStrings.current.actionRetry) }
                        }
                    }
                }
                ui == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                ui.folders.isEmpty() && ui.files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(LocalStrings.current.folderEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                gridView -> FilesGrid(
                    ui = ui,
                    thumbs = thumbs,
                    menuFor = menuFor,
                    setMenuFor = { menuFor = it },
                    onOpenFolder = onOpenFolder,
                    onRenameFolder = { renameTarget = Triple(true, it.id, it.name) },
                    onDeleteFolder = { deleting = it },
                    onShareFolder = { shareTarget = it },
                    onOpenFile = { openOrPreview(it, ui.files, onPreview, repo) },
                    onDownloadFile = { saver(it.name, it.mimeType) { repo.download(it.id) } },
                    onRenameFile = { renameTarget = Triple(false, it.id, it.name) },
                    onMoveFile = { moveTarget = MoveTarget(it.id, it.folderId, it.name) },
                    onDeleteFile = { deleting = it },
                    onShareFile = { shareTarget = it },
                    selection = selectedFiles.value.ifEmpty { null },
                    onToggleSelect = { file ->
                        selectedFiles.value = selectedFiles.value.toMutableSet().apply {
                            if (!add(file.id)) remove(file.id)
                        }
                    },
                )
                else -> {
                // Bottom clearance lets the last rows scroll clear of the
                // floating glass bar (content still flows behind it).
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 112.dp),
                ) {
                    items(ui.folders, key = { "f_${it.id}" }) { folder ->
                        FolderRow(
                            folder = folder,
                            onOpen = { onOpenFolder(folder) },
                            onRename = { renameTarget = Triple(true, folder.id, folder.name) },
                            onDelete = { deleting = folder },
                            onShare = { shareTarget = folder },
                            menuFor = menuFor,
                            setMenuFor = { menuFor = it },
                        )
                        HorizontalDivider()
                    }
                    items(ui.files, key = { "d_${it.id}" }) { file ->
                        FileRow(
                            file = file,
                            thumbs = thumbs,
                            onOpen = {
                                openOrPreview(file, ui.files, onPreview, repo)
                            },
                            onDownload = { saver(file.name, file.mimeType) { repo.download(file.id) } },
                            onRename = { renameTarget = Triple(false, file.id, file.name) },
                            onMove = { moveTarget = MoveTarget(file.id, file.folderId, file.name) },
                            onDelete = { deleting = file },
                            onShare = { shareTarget = file },
                            menuFor = menuFor,
                            setMenuFor = { menuFor = it },
                            selection = selectedFiles.value.ifEmpty { null },
                            onToggleSelect = {
                                selectedFiles.value = selectedFiles.value.toMutableSet().apply {
                                    if (!add(file.id)) remove(file.id)
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
                }
            }
        }
    }

    if (showNewFolder) {
        TextEntryDialog(
            title = LocalStrings.current.newFolder,
            initial = "",
            label = LocalStrings.current.fieldFolderName,
            confirmLabel = LocalStrings.current.actionCreate,
            onDismiss = { showNewFolder = false },
        ) { name ->
            scope.launch {
                repo.createFolder(path.lastOrNull()?.id, name).fold(
                    onSuccess = {
                        showNewFolder = false
                        reload()
                    },
                    onFailure = { snackbar.showSnackbar(msg(it, I18n.strings.createFailed)) },
                )
            }
        }
    }

    if (showUploadLocation) {
        UploadLocationDialog(
            repo = repo,
            onDismiss = { showUploadLocation = false },
        ) { id, name ->
            showUploadLocation = false
            uploadTarget = id to name
            picker()
        }
    }

    renameTarget?.let { (isFolder, id, initial) ->
        TextEntryDialog(
            title = if (isFolder) LocalStrings.current.renameFolder else LocalStrings.current.renameFile,
            initial = initial,
            label = LocalStrings.current.fieldNewName,
            confirmLabel = LocalStrings.current.actionConfirm,
            onDismiss = { renameTarget = null },
        ) { name ->
            scope.launch {
                val r = if (isFolder) repo.renameFolder(id, name) else repo.updateFile(id, name, null)
                r.fold(
                    onSuccess = {
                        renameTarget = null
                        reload()
                    },
                    onFailure = { snackbar.showSnackbar(msg(it, I18n.strings.renameFailed)) },
                )
            }
        }
    }

    moveTarget?.let { target ->
        MoveDialog(
            repo = repo,
            target = target,
            onDismiss = { moveTarget = null },
        ) { destination ->
            scope.launch {
                // Pass the sentinel through: server maps "root" to a root move; null keeps
                // the folder unchanged. Never collapse root to null here.
                repo.updateFile(target.fileId, null, destination).fold(
                    onSuccess = {
                        moveTarget = null
                        reload()
                    },
                    onFailure = { snackbar.showSnackbar(msg(it, I18n.strings.moveFailed)) },
                )
            }
        }
    }

    deleting?.let { item ->
        val isFolder = item is FolderDto
        val name = when (item) {
            is FolderDto -> item.name
            is FileDto -> item.name
            else -> ""
        }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (isFolder) LocalStrings.current.deleteFolder else LocalStrings.current.deleteFile) },
            text = { Text(LocalStrings.current.confirmDelete(name)) },
            confirmButton = {
                TextButton(onClick = {
                    val d = deleting
                    deleting = null
                    scope.launch {
                        val r = when (d) {
                            is FolderDto -> repo.deleteFolder(d.id)
                            is FileDto -> repo.deleteFile(d.id)
                            else -> return@launch
                        }
                        r.fold(
                            onSuccess = { reload() },
                            onFailure = { snackbar.showSnackbar(msg(it, I18n.strings.deleteFailed)) },
                        )
                    }
                }) { Text(LocalStrings.current.actionDelete) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(LocalStrings.current.actionCancel) } },
        )
    }

    shareTarget?.let { target ->
        val isFolder = target is FolderDto
        val id = when (target) {
            is FolderDto -> target.id
            is FileDto -> target.id
            else -> ""
        }
        ShareDialog(
            repo = repo,
            isFolder = isFolder,
            targetId = id,
            onDismiss = { shareTarget = null },
            onError = { e -> scope.launch { snackbar.showSnackbar(msg(e, I18n.strings.operationFailed)) } },
        )
    }
}

@Composable
private fun FilesGrid(
    ui: ContentsUi,
    thumbs: ThumbnailLoader,
    menuFor: Any?,
    setMenuFor: (Any?) -> Unit,
    onOpenFolder: (FolderDto) -> Unit,
    onRenameFolder: (FolderDto) -> Unit,
    onDeleteFolder: (FolderDto) -> Unit,
    onShareFolder: (FolderDto) -> Unit,
    onOpenFile: (FileDto) -> Unit,
    onDownloadFile: (FileDto) -> Unit,
    onRenameFile: (FileDto) -> Unit,
    onMoveFile: (FileDto) -> Unit,
    onDeleteFile: (FileDto) -> Unit,
    onShareFile: (FileDto) -> Unit,
    selection: Set<String>? = null,
    onToggleSelect: (FileDto) -> Unit = {},
) {
    // Grid tiles for files; folders keep a full-width leading section so the
    // hierarchy stays scannable before the file cards.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier.fillMaxSize(),
        // Bottom clearance for the floating glass bar (content flows behind).
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ui.folders, key = { "f_${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { folder ->
            FolderGridRow(
                folder = folder,
                onOpen = { onOpenFolder(folder) },
                onRename = { onRenameFolder(folder) },
                onDelete = { onDeleteFolder(folder) },
                onShare = { onShareFolder(folder) },
                menuFor = menuFor,
                setMenuFor = setMenuFor,
            )
        }
        items(ui.files, key = { "d_${it.id}" }) { file ->
            FileTile(
                file = file,
                thumbs = thumbs,
                onOpen = { onOpenFile(file) },
                selection = selection,
                onToggleSelect = { onToggleSelect(file) },
                onDownload = { onDownloadFile(file) },
                onRename = { onRenameFile(file) },
                onMove = { onMoveFile(file) },
                onDelete = { onDeleteFile(file) },
                onShare = { onShareFile(file) },
                menuFor = menuFor,
                setMenuFor = setMenuFor,
            )
        }
    }
}

@Composable
private fun FolderGridRow(
    folder: FolderDto,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    menuFor: Any?,
    setMenuFor: (Any?) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                folder.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { setMenuFor(if (menuFor === folder) null else folder) }) {
                    Icon(Icons.Default.MoreVert, contentDescription = LocalStrings.current.moreActions)
                }
                DropdownMenu(expanded = menuFor === folder, onDismissRequest = { setMenuFor(null) }) {
                    DropdownMenuItem(
                        text = { Text(LocalStrings.current.actionRename) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            setMenuFor(null)
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(LocalStrings.current.actionShare) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = {
                            setMenuFor(null)
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(LocalStrings.current.actionDelete) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            setMenuFor(null)
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FileTile(
    file: FileDto,
    thumbs: ThumbnailLoader,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    menuFor: Any?,
    setMenuFor: (Any?) -> Unit,
    selection: Set<String>? = null,
    onToggleSelect: () -> Unit = {},
) {
    val isSelected = selection?.contains(file.id) == true
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = { if (selection != null) onToggleSelect() else onOpen() },
                onLongClick = onToggleSelect,
            )
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp),
            contentAlignment = Alignment.Center,
        ) {
            FileThumbnail(file, thumbs, edge = 84.dp)
            if (selection != null) {
                Icon(
                    if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            file.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "${formatFileSize(file.size)} · ${formatDateTime(file.updatedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            IconButton(onClick = { setMenuFor(if (menuFor === file) null else file) }) {
                Icon(Icons.Default.MoreVert, contentDescription = LocalStrings.current.moreActions)
            }
            DropdownMenu(expanded = menuFor === file, onDismissRequest = { setMenuFor(null) }) {
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionRename) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        setMenuFor(null)
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionMove) },
                    leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                    onClick = {
                        setMenuFor(null)
                        onMove()
                    },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionDownload) },
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                    onClick = {
                        setMenuFor(null)
                        onDownload()
                    },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionShare) },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = {
                        setMenuFor(null)
                        onShare()
                    },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionDelete) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        setMenuFor(null)
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun FolderRow(
    folder: FolderDto,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    menuFor: Any?,
    setMenuFor: (Any?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(folder.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            IconButton(onClick = { setMenuFor(if (menuFor === folder) null else folder) }) {
                Icon(Icons.Default.MoreVert, contentDescription = LocalStrings.current.moreActions)
            }
            DropdownMenu(expanded = menuFor === folder, onDismissRequest = { setMenuFor(null) }) {
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionRename) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        setMenuFor(null)
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionShare) },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = {
                        setMenuFor(null)
                        onShare()
                    },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionDelete) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        setMenuFor(null)
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    file: FileDto,
    thumbs: ThumbnailLoader,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    menuFor: Any?,
    setMenuFor: (Any?) -> Unit,
    selection: Set<String>? = null,
    onToggleSelect: () -> Unit = {},
) {
    val isSelected = selection?.contains(file.id) == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selection != null) onToggleSelect() else onOpen() },
                onLongClick = onToggleSelect,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumbnail(file, thumbs, edge = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatFileSize(file.size)} · ${formatDateTime(file.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selection != null) {
            // The circle where the 3-dot used to be.
            IconButton(onClick = onToggleSelect) {
                Icon(
                    if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
        Box {
            IconButton(onClick = { setMenuFor(if (menuFor === file) null else file) }) {
                Icon(Icons.Default.MoreVert, contentDescription = LocalStrings.current.moreActions)
            }
            DropdownMenu(expanded = menuFor === file, onDismissRequest = { setMenuFor(null) }) {
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionRename) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        setMenuFor(null)
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionMove) },
                    leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                    onClick = {
                        setMenuFor(null)
                        onMove()
                    },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionDownload) },
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                    onClick = {
                        setMenuFor(null)
                        onDownload()
                    },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionShare) },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = {
                        setMenuFor(null)
                        onShare()
                    },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionDelete) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        setMenuFor(null)
                        onDelete()
                    },
                )
            }
        }
        }
    }
}

@Composable
private fun TextEntryDialog(
    title: String,
    initial: String,
    label: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(LocalStrings.current.actionCancel) } },
    )
}

@Composable
private fun MoveDialog(
    repo: FilesRepository,
    target: MoveTarget,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // Selection uses sentinel ROOT_FOLDER_ID for the root directory.
    var selected by remember { mutableStateOf(target.fromFolderId ?: ROOT_FOLDER_ID) }
    var folders by remember { mutableStateOf<List<FolderDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun loadInto(id: String?) {
        val r = repo.contents(id ?: ROOT_FOLDER_ID)
        r.fold(
            onSuccess = {
                folders = it.folders
                loading = false
            },
            onFailure = {
                error = it.message ?: I18n.strings.loadFailed
                loading = false
            },
        )
    }

    LaunchedEffect(Unit) { loadInto(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(LocalStrings.current.moveToTitle(target.fileName)) },
        text = {
            Box(modifier = Modifier.height(280.dp)) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    else -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = ROOT_FOLDER_ID }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(LocalStrings.current.rootFolder)
                            Spacer(Modifier.weight(1f))
                            if (selected == ROOT_FOLDER_ID) {
                                Text(LocalStrings.current.selected, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        folders.forEach { f ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = f.id }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.weight(1f))
                                if (selected == f.id) {
                                    Text(LocalStrings.current.selected, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && selected != target.fromFolderId,
                onClick = { onConfirm(selected) },
            ) { Text(LocalStrings.current.moveHere) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(LocalStrings.current.actionCancel) } },
    )
}

/** Validity presets for new shares; null = permanent (never expires). */
@Composable
private fun shareTtlOptions(): List<Pair<Long?, String>> = listOf(
    null to I18n.strings.expiryNever,
    24L to I18n.strings.expiryOneDay,
    24L * 7 to I18n.strings.expirySevenDays,
    24L * 30 to LocalStrings.current.expiryThirtyDays,
)

/**
 * Share management dialog: pick a validity window, create a link (the raw
 * token is shown once), copy full URL, list existing links, revoke.
 * Flat surfaces only - no gradients, no hover states.
 */
@Composable
private fun ShareDialog(
    repo: FilesRepository,
    isFolder: Boolean,
    targetId: String,
    onDismiss: () -> Unit,
    onError: (Throwable) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var existing by remember { mutableStateOf<List<ShareDto>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var ttlIndex by remember { mutableStateOf(0) }
    var freshUrl by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            repo.listShares(fileId = if (!isFolder) targetId else null, folderId = if (isFolder) targetId else null)
                .fold(
                    onSuccess = { existing = it.shares },
                    onFailure = { onError(it) },
                )
        }
    }

    fun copy(url: String) {
        scope.launch {
            val ok = copyToClipboard(url)
            copied = ok
            if (!ok) onError(IllegalStateException(I18n.strings.copyFailedManual))
        }
    }

    LaunchedEffect(targetId) { refresh() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isFolder) LocalStrings.current.shareFolder else LocalStrings.current.shareFile) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Fresh link banner: shown once right after creation.
                freshUrl?.let { url ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                LocalStrings.current.shareLinkCreated,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(url, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { copy(url) }) { Text(if (copied) LocalStrings.current.copied else LocalStrings.current.copyLink) }
                                TextButton(onClick = {
                                    // Revoke right from the banner: closing a link
                                    // must not require hunting for it later.
                                    scope.launch {
                                        existing.firstOrNull()?.let { s ->
                                            repo.revokeShare(s.id).fold(
                                                onSuccess = { freshUrl = null; refresh() },
                                                onFailure = { onError(it) },
                                            )
                                        }
                                    }
                                }) { Text(LocalStrings.current.actionRevokeLink, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Text(LocalStrings.current.fieldExpiry, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                val ttlOptions = shareTtlOptions()
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ttlOptions.forEachIndexed { i, (_, label) ->
                        SegmentedButton(
                            selected = ttlIndex == i,
                            onClick = { ttlIndex = i },
                            shape = SegmentedButtonDefaults.itemShape(i, ttlOptions.size),
                        ) { Text(label, maxLines = 1) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !creating,
                    onClick = {
                        creating = true
                        scope.launch {
                            val hours = ttlOptions[ttlIndex].first
                            repo.createShare(fileId = if (!isFolder) targetId else null, folderId = if (isFolder) targetId else null, expiresInHours = hours)
                                .fold(
                                    onSuccess = { share ->
                                        freshUrl = repo.shareUrl(share.url)
                                        copied = false
                                        creating = false
                                        refresh()
                                    },
                                    onFailure = {
                                        creating = false
                                        onError(it)
                                    },
                                )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (creating) LocalStrings.current.creating else LocalStrings.current.createShareLink) }

                if (existing.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(LocalStrings.current.existingLink, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    existing.forEach { share ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    share.expiresAt?.let { LocalStrings.current.expiresAt(formatDateTime(it)) } ?: LocalStrings.current.neverExpires,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    LocalStrings.current.shareStats(share.viewCount.toString(), share.downloadCount.toString()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    repo.revokeShare(share.id).fold(
                                        onSuccess = { refresh() },
                                        onFailure = { onError(it) },
                                    )
                                }
                            }) { Text(LocalStrings.current.actionClose) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(LocalStrings.current.actionDone) } },
    )
}

/**
 * Destination picker for new uploads: the user chooses the folder first, then
 * picks files, so the upload lands where they intended regardless of the
 * folder they started from. Navigation starts at the root; the confirm button
 * passes the chosen folder (null = root) with a display name.
 */
@Composable
private fun UploadLocationDialog(
    repo: FilesRepository,
    onDismiss: () -> Unit,
    onPicked: (String?, String) -> Unit,
) {
    var stack by remember { mutableStateOf(listOf<FolderDto>()) }
    var entries by remember { mutableStateOf(listOf<FolderDto>()) }
    var loading by remember { mutableStateOf(true) }
    val currentId = stack.lastOrNull()?.id

    LaunchedEffect(stack) {
        loading = true
        repo.contents(currentId ?: "root").fold(
            onSuccess = { entries = it.folders },
            onFailure = { entries = emptyList() },
        )
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(LocalStrings.current.pickUploadLocation) },
        text = {
            Column(Modifier.heightIn(max = 360.dp)) {
                if (stack.isNotEmpty()) {
                    TextButton(onClick = { stack = stack.dropLast(1) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stack.dropLast(1).lastOrNull()?.name ?: LocalStrings.current.rootFolder)
                    }
                }
                if (loading) {
                    Text(LocalStrings.current.loading, style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn {
                    items(entries, key = { it.id }) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { stack = stack + folder }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text(folder.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val rootLabel = LocalStrings.current.rootFolder
            TextButton(onClick = {
                onPicked(currentId, stack.lastOrNull()?.name ?: rootLabel)
            }) { Text(LocalStrings.current.actionConfirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(LocalStrings.current.actionCancel) }
        },
    )
}
