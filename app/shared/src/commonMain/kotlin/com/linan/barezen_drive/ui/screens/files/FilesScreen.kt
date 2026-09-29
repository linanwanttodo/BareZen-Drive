package com.linan.barezen_drive.ui.screens.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import com.linan.barezen_drive.ui.component.FileTransferEntryIcon
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha
import com.linan.barezen_drive.core.dto.ErrorCodes
import com.linan.barezen_drive.ui.shell.BottomBarClearance
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.FileVersionDto
import com.linan.barezen_drive.core.dto.FolderDto
import com.linan.barezen_drive.core.dto.ShareDto
import com.linan.barezen_drive.data.api.ApiFailure
import com.linan.barezen_drive.platform.copyToClipboard
import com.linan.barezen_drive.data.local.AppPreferences
import com.linan.barezen_drive.data.repo.AlbumFolder
import com.linan.barezen_drive.data.library.LibraryRevision
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.data.upload.UploadManager
import com.linan.barezen_drive.platform.PickedFile
import com.linan.barezen_drive.platform.rememberFilePicker
import com.linan.barezen_drive.data.transfer.TransferCenter
import com.linan.barezen_drive.data.transfer.TransferItem
import com.linan.barezen_drive.data.transfer.TransferKind
import com.linan.barezen_drive.data.transfer.TransferLane
import com.linan.barezen_drive.data.transfer.TransferPhase
import com.linan.barezen_drive.data.transfer.rememberDownloadSaver
import com.linan.barezen_drive.ui.component.SkeletonList
import com.linan.barezen_drive.ui.component.onHoverChanged
import com.linan.barezen_drive.ui.media.FileThumbnail
import com.linan.barezen_drive.ui.media.ThumbnailLoader
import com.linan.barezen_drive.ui.media.formatDateTime
import com.linan.barezen_drive.ui.screens.preview.PreviewKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.i18n.LocalStrings
import kotlin.time.Duration.Companion.milliseconds

private const val ROOT_FOLDER_ID = "root"

/**
 * One folder's listing. [nextCursor] is non-null while more files exist: the
 * list shows a load-more footer and keeps appending, instead of downloading a
 * ten-thousand-file folder in one response and rebinding every row on scroll.
 */
/** Rows per request; the same page size the album grid uses. */
private const val PAGE_SIZE = 200

private data class ContentsUi(
    val folders: List<FolderDto>,
    val files: List<FileDto>,
    val nextCursor: String? = null,
)

private data class MoveTarget(val fileId: String, val fromFolderId: String?, val fileName: String)

/**
 * Every action a file row or tile can perform, dispatched through ONE callback.
 *
 * The rows used to take nine to eleven lambdas (`onOpen`, `onRename`, ...),
 * all rebuilt whenever the list recomposed, so no row could ever skip and one
 * progress tick rebuilt every visible row's callbacks. A row now takes the
 * item plus a single dispatch function; everything else it receives is a
 * stable reference.
 *
 * The overflow menu is dispatched here too, rather than through a per-item
 * `setMenuOpen` lambda - a lambda built from the item's key is a new instance
 * on every recomposition, which is exactly the churn this removes.
 */
private enum class FileAction { OPEN, DOWNLOAD, RENAME, MOVE, DELETE, SHARE, FAVORITE, ARCHIVE, VERSIONS, TOGGLE_SELECT, TOGGLE_MENU }

/** Folders have a much smaller menu; kept separate so no row can fire a file action. */
private enum class FolderAction { OPEN, RENAME, DELETE, SHARE, TOGGLE_MENU }

/** Path segments kept visible before the middle of the breadcrumb folds away. */
private const val MAX_CRUMBS = 3

/**
 * File-transfer rows rendered inline above the listing. The rest live in the
 * transfer centre (one tap away), so a long tail of old FAILED rows can never
 * push the folder listing off the screen.
 */
private const val MAX_INLINE_TRANSFERS = 3

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
    thumbs: ThumbnailLoader,
    onOpenFolder: (FolderDto) -> Unit,
    onOpenUploads: () -> Unit = {},
    avatar: @Composable () -> Unit = {},
    onJumpTo: (Int) -> Unit,
    onPreview: (List<FileDto>, Int) -> Unit,
    themeToggle: (@Composable () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    // remembered, not bare: a fresh SnackbarHostState per recomposition means
    // the host renders a different instance than the one a launched coroutine
    // holds, so showSnackbar's message is never drawn and the call never
    // returns - it suspends waiting for a dismissal that cannot happen.
    val snackbar = remember { SnackbarHostState() }
    val currentPath by rememberUpdatedState(path)
    val folderId = path.lastOrNull()?.id ?: ROOT_FOLDER_ID

    var state by remember(path) { mutableStateOf<ContentsUi?>(null) }
    // Paging: 200 rows a page, matching the album page. Appending keeps the
    // rows already on screen (and their scroll position) untouched.
    var loadingMore by remember(path) { mutableStateOf(false) }
    var loadMoreError by remember(path) { mutableStateOf<String?>(null) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var pendingUploads by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var uploading by remember { mutableStateOf(false) }
    // Which row's overflow menu is open, as "f_<id>" / "d_<id>". A key rather
    // than the DTO itself: identity comparison is what made a row rebuild its
    // menu whenever the listing was replaced, and two equal DTOs could open
    // each other's menu.
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Triple<Boolean, String, String>?>(null) }
    var moveTarget by remember { mutableStateOf<MoveTarget?>(null) }
    var deleting by remember { mutableStateOf<Any?>(null) }
    // Share dialog target: folder or file DTO (menu "share" opens it).
    var shareTarget by remember { mutableStateOf<Any?>(null) }
    // A same-named live file exists at the destination: the server answers the
    // complete call with NAME_CONFLICT, the queue parks here and the dialog
    // asks whether to replace it (the old content survives as a version).
    var overwritePrompt by remember { mutableStateOf<PickedFile?>(null) }
    // Where the parked pick was headed, its already computed hash (so the
    // retry does not re-read the file) and the picks still behind it.
    var overwriteTarget by remember { mutableStateOf<String?>(null) }
    var overwriteHash by remember { mutableStateOf<String?>(null) }
    var queuedPicks by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    // Version history dialog: the file whose revisions are shown, the rows and
    // whether the list is still loading.
    var versionsFor by remember { mutableStateOf<FileDto?>(null) }
    var versions by remember { mutableStateOf<List<FileVersionDto>>(emptyList()) }
    var versionsLoading by remember { mutableStateOf(false) }
    // A failed version load used to raise one snackbar and then fall through to
    // `versions.isEmpty()`, so the dialog announced "no earlier versions" for a
    // file that may well have them - a broken request was rendered as an empty
    // history, and nothing on screen offered a way back.
    var versionsError by remember { mutableStateOf<String?>(null) }
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
            repo.contents(folderId, PAGE_SIZE).fold(
                onSuccess = {
                    // At the root level the dedicated album folder is pinned first.
                    val folders = if (folderId == ROOT_FOLDER_ID) {
                        it.folders.sortedByDescending { f -> f.name == AlbumFolder.ROOT_NAME }
                    } else it.folders
                    state = ContentsUi(folders, it.files, it.nextCursor)
                    loadError = null
                    loadMoreError = null
                },
                onFailure = { loadError = msg(it, I18n.strings.loadFailed); snackbar.showSnackbar(loadError ?: I18n.strings.loadFailed) },
            )
        }
    }

    /** Fetches the next page and appends it; the list is never rebuilt. */
    fun loadMore() {
        val cursor = state?.nextCursor ?: return
        if (loadingMore) return
        loadingMore = true
        loadMoreError = null
        scope.launch {
            repo.contents(folderId, PAGE_SIZE, cursor).fold(
                onSuccess = { page ->
                    val current = state ?: return@fold
                    // Append, de-duplicated by id: a file renamed between pages
                    // can shift the cursor and hand back a row we already show.
                    val known = current.files.mapTo(HashSet()) { it.id }
                    state = current.copy(
                        files = current.files + page.files.filter { known.add(it.id) },
                        nextCursor = page.nextCursor,
                    )
                    loadingMore = false
                },
                onFailure = {
                    // The rows already loaded stay put; the footer offers a retry.
                    loadMoreError = msg(it, I18n.strings.loadFailed)
                    loadingMore = false
                },
            )
        }
    }

    // Flag toggles for the file menu: favoriting keeps the row in place,
    // archiving hides it; either way the list reloads from the server.
    fun toggleFavorite(file: FileDto) {
        scope.launch {
            repo.setFavorite(file.id, !file.isFavorite).fold(
                onSuccess = { reload() },
                onFailure = { snackbar.showSnackbar(I18n.strings.operationFailed) },
            )
        }
    }

    fun toggleArchived(file: FileDto) {
        val archiving = file.archivedAt == null
        scope.launch {
            repo.setArchived(file.id, archiving).fold(
                onSuccess = {
                    // Past tense, and the right one: the menu label doubles as
                    // both "归档" and "取消归档", so reusing it would show a
                    // button name as if it were a result.
                    snackbar.showSnackbar(
                        if (archiving) I18n.strings.archived else I18n.strings.unarchived,
                    )
                    reload()
                },
                onFailure = { snackbar.showSnackbar(I18n.strings.operationFailed) },
            )
        }
    }

    // ---- Uploads ----

    /**
     * Uploads [picks] one after another into [target]. A NAME_CONFLICT parks the
     * rest of the queue and opens the overwrite dialog instead of failing the
     * batch: answering it re-drives `uploadAll` with the parked pick first.
     *
     * The retry is cheap by design: the rejected session stays open server-side
     * with every chunk stored, so the overwrite attempt resume-matches it in
     * upload/init and goes straight to complete (no byte is sent twice).
     */
    suspend fun uploadAll(picks: List<PickedFile>, target: String?, overwrite: Boolean = false) {
        uploading = true
        var index = 0
        while (index < picks.size) {
            val p = picks[index]
            // Remember the hash the manager derived so an overwrite retry can
            // reuse it instead of streaming the whole file again.
            var freshHash: String? = null
            val outcome = uploader.upload(
                p, target,
                cachedSha256 = null,
                onHashed = { h -> freshHash = h },
                overwrite = overwrite,
            )
            val e = outcome.exceptionOrNull()
            if (e is ApiFailure.Http && e.code == ErrorCodes.NAME_CONFLICT && !overwrite) {
                overwritePrompt = p
                overwriteTarget = target
                overwriteHash = freshHash
                queuedPicks = picks.drop(index + 1)
                uploading = false
                return
            }
            if (e != null) snackbar.showSnackbar(msg(e, I18n.strings.uploadFailedNamed(p.name)))
            index++
        }
        uploading = false
        reload()
    }

    /** Re-drives the parked pick, optionally replacing the file of the same name. */
    fun resolveOverwrite(replace: Boolean) {
        val parked = overwritePrompt ?: return
        val rest = queuedPicks
        val target = overwriteTarget
        val hash = overwriteHash
        overwritePrompt = null
        queuedPicks = emptyList()
        overwriteHash = null
        if (!replace) {
            scope.launch {
                snackbar.showSnackbar(I18n.strings.uploadSkippedNamed(parked.name))
                uploadAll(rest, target)
            }
            return
        }
        scope.launch {
            uploading = true
            val r = hash?.let {
                uploader.upload(parked, target, cachedSha256 = it, overwrite = true)
            } ?: uploader.upload(parked, target, overwrite = true)
            r.exceptionOrNull()?.let { e ->
                snackbar.showSnackbar(msg(e, I18n.strings.uploadFailedNamed(parked.name)))
            }
            uploadAll(rest, target)
        }
    }

    // ---- Version history ----

    fun openVersions(file: FileDto) {
        versionsFor = file
        versions = emptyList()
        versionsLoading = true
        versionsError = null
        scope.launch {
            repo.fileVersions(file.id).fold(
                onSuccess = { versions = it; versionsError = null },
                // No snackbar: the dialog is right there and now says what went
                // wrong, with a retry next to it.
                onFailure = { versionsError = msg(it, I18n.strings.loadFailed) },
            )
            versionsLoading = false
        }
    }

    fun restoreVersion(file: FileDto, version: FileVersionDto) {
        scope.launch {
            repo.restoreFileVersion(file.id, version.id).fold(
                onSuccess = {
                    snackbar.showSnackbar(I18n.strings.versionRestored)
                    reload()
                    openVersions(file)
                },
                onFailure = { snackbar.showSnackbar(msg(it, I18n.strings.operationFailed)) },
            )
        }
    }

    fun dropVersion(file: FileDto, version: FileVersionDto) {
        scope.launch {
            repo.deleteFileVersion(file.id, version.id).fold(
                onSuccess = { openVersions(file) },
                onFailure = { snackbar.showSnackbar(msg(it, I18n.strings.operationFailed)) },
            )
        }
    }

    val picker = rememberFilePicker { picks ->
        if (picks.isNotEmpty()) pendingUploads = picks
    }
    val saver = rememberDownloadSaver(repo) {
        snackbar.showSnackbar(I18n.strings.downloadFailed)
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

    // ---- Row dispatch (the single callback every row/tile takes) ----
    //
    // Two layers on purpose. The outer lambda is remembered, so its identity
    // never changes and a row can be skipped; it reads the real work out of a
    // rememberUpdatedState, which is rewritten on every recomposition. Without
    // that split the rows would hold a callback closing over the values of the
    // composition that created it - a stale listing, a stale snackbar host.
    val fileActionImpl = rememberUpdatedState<(FileAction, FileDto) -> Unit> { action, file ->
        when (action) {
            FileAction.OPEN -> openOrPreview(file, state?.files ?: emptyList(), onPreview, repo)
            FileAction.DOWNLOAD -> saver(file)
            FileAction.RENAME -> renameTarget = Triple(false, file.id, file.name)
            FileAction.MOVE -> moveTarget = MoveTarget(file.id, file.folderId, file.name)
            FileAction.DELETE -> deleting = file
            FileAction.SHARE -> shareTarget = file
            FileAction.FAVORITE -> toggleFavorite(file)
            FileAction.ARCHIVE -> toggleArchived(file)
            FileAction.VERSIONS -> openVersions(file)
            FileAction.TOGGLE_SELECT -> selectedFiles.value = selectedFiles.value.toMutableSet().apply {
                if (!add(file.id)) remove(file.id)
            }
            FileAction.TOGGLE_MENU -> {
                val key = "d_${file.id}"
                menuFor = if (menuFor == key) null else key
            }
        }
    }
    val folderActionImpl = rememberUpdatedState<(FolderAction, FolderDto) -> Unit> { action, folder ->
        when (action) {
            FolderAction.OPEN -> onOpenFolder(folder)
            FolderAction.RENAME -> renameTarget = Triple(true, folder.id, folder.name)
            FolderAction.DELETE -> deleting = folder
            FolderAction.SHARE -> shareTarget = folder
            FolderAction.TOGGLE_MENU -> {
                val key = "f_${folder.id}"
                menuFor = if (menuFor == key) null else key
            }
        }
    }
    val onFileAction: (FileAction, FileDto) -> Unit = remember {
        { action: FileAction, file: FileDto -> fileActionImpl.value(action, file) }
    }
    val onFolderAction: (FolderAction, FolderDto) -> Unit = remember {
        { action: FolderAction, folder: FolderDto -> folderActionImpl.value(action, folder) }
    }
    val dismissMenu: () -> Unit = remember { { menuFor = null } }

    // Drain pending picks sequentially. Picks that arrive while an upload is
    // running stay queued and go out in the next round instead of silently
    // vanishing (Compose state is UI-thread confined, so the take-and-clear
    // below cannot interleave with a picker callback).
    LaunchedEffect(Unit) {
        snapshotFlow { pendingUploads.isNotEmpty() }.collect { nonEmpty ->
            if (!nonEmpty) return@collect
            while (pendingUploads.isNotEmpty()) {
                val picks = pendingUploads
                pendingUploads = emptyList()
                // The dialog choice is authoritative when present: its id is
                // null for an explicit "root" pick and must NOT fall back to
                // the folder currently on screen. It is consumed together
                // with the picks - clearing it only after the round would
                // wipe a choice the user made while this round was uploading.
                val choice = uploadTarget
                uploadTarget = null
                val target = if (choice != null) choice.first else currentPath.lastOrNull()?.id
                uploadAll(picks, target)
                if (overwritePrompt != null) return@collect
            }
        }
    }

    // A fresh folder clears the selection - it belongs to one listing.
    LaunchedEffect(path) {
        selectedFiles.value = emptySet()
        reload()
    }

    // A finished transfer has to show up in the listing without leaving the tab.
    // Kept out of the effect above on purpose: re-running that one would clear a
    // selection the user is in the middle of making.
    //
    // The delay is the debounce: a backup bumps this once per photo and every
    // bump restarts the effect, cancelling the wait, so only the last one of a
    // burst reaches the network.
    val revision by LibraryRevision.value.collectAsState()
    LaunchedEffect(revision) {
        if (revision == 0L) return@LaunchedEffect
        delay(400.milliseconds)
        reload()
    }

    // Upload progress comes from the transfer centre, not from the manager.
    // The manager used to keep ONE progress slot, so with two files uploading
    // at once the strip showed whichever was written last and the other looked
    // frozen - one file's bar wearing another file's name, which is exactly
    // what the centre's per-row model was built to avoid. Rows are newest
    // first; DONE ones drop out so the strip follows live work instead of
    // growing with the session's history.
    val transferRows by TransferCenter.items.collectAsState()
    val fileTransfers = remember(transferRows) {
        transferRows.filter {
            it.lane == TransferLane.FILE &&
                it.kind == TransferKind.UPLOAD &&
                it.phase != TransferPhase.DONE
        }
    }

    Scaffold(
        // Lift the snackbar above the floating bottom bar: at scaffold
        // bottom it sits behind the translucent glass and reads as a
        // second, stacked navigation bar (seen on connection errors).
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = BottomBarClearance)) },
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
        topBar = {
            Column {
                TopAppBar(
                    // Transparent so the wallpaper layer MainShell paints
                    // behind this tab shows through; the default container
                    // color is opaque and renders as a hard band up top.
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
                        FileTransferEntryIcon(onClick = onOpenUploads)
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
                // Breadcrumb: plain text links, not buttons. TextButton gave
                // every crumb a chip's worth of height and horizontal padding,
                // so the path read as a row of buttons and pushed the file list
                // down. 14sp with a 6dp hit padding keeps the row ~28dp tall
                // while staying comfortable to tap.
                //
                // The path is bounded, not scrollable: it used to grow a segment
                // per level inside a horizontalScroll, which on a narrow screen
                // pushed the action icons out of reach - and the trailing
                // ellipsis never fired, because nothing constrained the text.
                // Deep paths fold to first + ellipsis + last two (where the user
                // is), and every name shrinks with an ellipsis of its own before
                // the row can overflow.
                val crumbStyle = MaterialTheme.typography.bodyMedium
                val folded = path.size > MAX_CRUMBS
                val visible = remember(path, folded) {
                    if (folded) listOf(0) + path.indices.toList().takeLast(MAX_CRUMBS - 1)
                    else path.indices.toList()
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = LocalStrings.current.rootFolder,
                        style = crumbStyle,
                        color = if (path.isEmpty()) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onJumpTo(-1) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    visible.forEachIndexed { slot, i ->
                        val f = path[i]
                        val isLast = i == path.lastIndex
                        Text(
                            text = " / ",
                            style = crumbStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (folded && slot == 1) {
                            // The folded middle. Not clickable on its own - the
                            // crumbs on either side are the reachable ones.
                            Text(
                                text = "…",
                                style = crumbStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        Text(
                            text = f.name,
                            style = crumbStyle,
                            color = if (isLast) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                // fill = false: the row only shrinks names when
                                // they no longer fit, and never pads a short
                                // path out to full width.
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onJumpTo(i) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        },
    ) { pad ->
        val ui = state
        // Pull-to-refresh around the whole content column: the list only
        // reloads on entry or after an action, so a change made elsewhere
        // (another device, the web client) never reached an open folder.
        var refreshing by remember { mutableStateOf(false) }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    repo.contents(folderId).fold(
                        onSuccess = {
                            val folders = if (folderId == ROOT_FOLDER_ID) {
                                it.folders.sortedByDescending { f -> f.name == AlbumFolder.ROOT_NAME }
                            } else it.folders
                            state = ContentsUi(folders, it.files)
                            loadError = null
                        },
                        onFailure = { e ->
                            // A silent failure would read as "nothing new";
                            // say what happened instead.
                            scope.launch { snackbar.showSnackbar(msg(e, I18n.strings.loadFailed)) }
                        },
                    )
                    refreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
        Column(
            modifier = Modifier.fillMaxSize(),
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
                                saver(it)
                            }
                        }
                        selectedFiles.value = emptySet()
                    }) { Icon(Icons.Default.Download, contentDescription = LocalStrings.current.actionDownload) }
                    // Sharing creates one link per file server-side, so the action
                    // only exists for a single selection; disabled (not hidden) on
                    // multi-select keeps the bar layout stable.
                    IconButton(
                        enabled = sel.size == 1,
                        onClick = {
                            shareTarget = ui.files.firstOrNull { it.id == sel.first() }
                            selectedFiles.value = emptySet()
                        },
                    ) { Icon(Icons.Default.Share, contentDescription = LocalStrings.current.actionShare) }
                    IconButton(onClick = {
                        // Delete every selected file, not just the first one.
                        deleting = ui.files.filter { it.id in sel }
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
            // One row per live transfer, not one bar for all of them.
            if (fileTransfers.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    fileTransfers.take(MAX_INLINE_TRANSFERS).forEach { row ->
                        UploadRow(
                            row = row,
                            onRetry = { scope.launch { uploader.retryFailed(row.id) } },
                        )
                    }
                    if (fileTransfers.size > MAX_INLINE_TRANSFERS) {
                        // The full list, with the rows this strip left out.
                        TextButton(onClick = onOpenUploads) { Text(LocalStrings.current.seeAll) }
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
                // Skeleton rows, not a spinner: the folder keeps its shape so
                // the list does not visibly jump when the rows land.
                ui == null -> SkeletonList(modifier = Modifier.fillMaxSize().padding(top = 8.dp))
                ui.folders.isEmpty() && ui.files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(LocalStrings.current.folderEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                gridView -> FilesGrid(
                    ui = ui,
                    thumbs = thumbs,
                    onFileAction = onFileAction,
                    onFolderAction = onFolderAction,
                    menuFor = menuFor,
                    dismissMenu = dismissMenu,
                    selectionMode = sel.isNotEmpty(),
                    selectedIds = sel,
                )
                else -> {
                // Bottom clearance lets the last rows scroll clear of the
                // floating glass bar (content still flows behind it).
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = BottomBarClearance),
                ) {
                    items(ui.folders, key = { "f_${it.id}" }) { folder ->
                        FolderRow(
                            folder = folder,
                            onAction = onFolderAction,
                            menuOpen = menuFor == "f_${folder.id}",
                            dismissMenu = dismissMenu,
                        )
                        HorizontalDivider()
                    }
                    items(ui.files, key = { "d_${it.id}" }) { file ->
                        FileRow(
                            file = file,
                            thumbs = thumbs,
                            onAction = onFileAction,
                            menuOpen = menuFor == "d_${file.id}",
                            dismissMenu = dismissMenu,
                            selectionMode = sel.isNotEmpty(),
                            selected = file.id in sel,
                        )
                        HorizontalDivider()
                    }
                    // Load-more footer. Reached by scrolling, not by a button:
                    // the next page is already known to exist, so making the user
                    // ask for it would just be a slower version of the same list.
                    if (ui.nextCursor != null || loadMoreError != null) {
                        item(key = "more") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    loadMoreError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            loadMoreError!!,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        TextButton(onClick = { loadMore() }) { Text(LocalStrings.current.actionRetry) }
                                    }
                                    loadingMore -> CircularProgressIndicator(Modifier.size(24.dp))
                                    else -> TextButton(onClick = { loadMore() }) {
                                        Text(LocalStrings.current.loadMore)
                                    }
                                }
                            }
                        }
                    }
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
                        // Creating is the one action here that leaves no trace in
                        // the list the user is looking at unless the new folder
                        // happens to scroll into view.
                        snackbar.showSnackbar(I18n.strings.folderCreated)
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
                        snackbar.showSnackbar(I18n.strings.fileRenamed)
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
                        // The item leaves this folder entirely: a silent success
                        // reads as a failed move.
                        snackbar.showSnackbar(I18n.strings.fileMoved)
                    },
                    onFailure = { snackbar.showSnackbar(msg(it, I18n.strings.moveFailed)) },
                )
            }
        }
    }

    deleting?.let { item ->
        val isFolder = item is FolderDto
        val batch = item as? List<*>
        val name = when (item) {
            is FolderDto -> item.name
            is FileDto -> item.name
            else -> ""
        }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { deleting = null },
            title = { Text(if (isFolder) LocalStrings.current.deleteFolder else LocalStrings.current.deleteFile) },
            text = {
                Text(
                    when {
                        batch != null -> LocalStrings.current.confirmDeleteFiles(batch.size)
                        isFolder -> LocalStrings.current.confirmDelete(name)
                        else -> LocalStrings.current.confirmDeleteFile(name)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val d = deleting
                    deleting = null
                    scope.launch {
                        var failed = 0
                        // Files land in the trash (soft delete); folders are still
                        // removed for good, so the snackbar only speaks for files.
                        var movedToTrash = false
                        when (d) {
                            is FolderDto -> if (repo.deleteFolder(d.id).isFailure) failed++
                            is FileDto -> {
                                movedToTrash = true
                                if (repo.deleteFile(d.id).isFailure) failed++
                            }
                            is List<*> -> {
                                val files = d.filterIsInstance<FileDto>()
                                if (files.isNotEmpty()) movedToTrash = true
                                files.forEach { f ->
                                    if (repo.deleteFile(f.id).isFailure) failed++
                                }
                            }
                        }
                        when {
                            failed > 0 -> snackbar.showSnackbar(I18n.strings.deleteFailed)
                            movedToTrash -> snackbar.showSnackbar(I18n.strings.movedToTrash)
                            // A folder delete is permanent, so it used to be the
                            // one delete with no receipt at all - the row vanished
                            // and nothing said whether it worked.
                            else -> snackbar.showSnackbar(I18n.strings.folderDeleted)
                        }
                        reload()
                    }
                }) { Text(LocalStrings.current.actionDelete, color = MaterialTheme.colorScheme.error) }
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
            onCreated = { scope.launch { snackbar.showSnackbar(I18n.strings.shareLinkReady) } },
        )
    }

    // Destination already holds a live file with this name. Overwriting keeps
    // the previous content as a restorable revision, so the row id, its share
    // links and its flags all survive; skipping leaves everything untouched.
    overwritePrompt?.let { pick ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { resolveOverwrite(replace = false) },
            title = { Text(LocalStrings.current.overwriteTitle) },
            text = { Text(LocalStrings.current.overwriteMessage(pick.name)) },
            confirmButton = {
                TextButton(onClick = { resolveOverwrite(replace = true) }) {
                    Text(LocalStrings.current.actionOverwrite)
                }
            },
            dismissButton = {
                TextButton(onClick = { resolveOverwrite(replace = false) }) {
                    Text(LocalStrings.current.actionSkip)
                }
            },
        )
    }

    versionsFor?.let { file ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { versionsFor = null },
            title = { Text(LocalStrings.current.versionHistory) },
            text = {
                Column(Modifier.heightIn(max = 340.dp)) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        versionsLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                LocalStrings.current.loadingVersions,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        // Third state, same shape as MoveDialog's: a failed
                        // request is not an empty history.
                        versionsError != null -> Column {
                            Text(
                                versionsError ?: LocalStrings.current.loadFailed,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { openVersions(file) }) {
                                Text(LocalStrings.current.actionRetry)
                            }
                        }
                        versions.isEmpty() -> Text(
                            LocalStrings.current.noVersions,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn(Modifier.fillMaxWidth()) {
                            items(versions, key = { it.id }) { v ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(LocalStrings.current.revisionLabel(v.revision))
                                        Text(
                                            "${formatFileSize(v.size)} · ${formatDateTime(v.createdAt)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { restoreVersion(file, v) }) {
                                        Text(LocalStrings.current.actionRestore)
                                    }
                                    TextButton(onClick = { dropVersion(file, v) }) {
                                        Text(
                                            LocalStrings.current.actionDelete,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { versionsFor = null }) { Text(LocalStrings.current.actionClose) }
            },
        )
    }
}

@Composable
private fun FilesGrid(
    ui: ContentsUi,
    thumbs: ThumbnailLoader,
    onFileAction: (FileAction, FileDto) -> Unit,
    onFolderAction: (FolderAction, FolderDto) -> Unit,
    menuFor: String?,
    dismissMenu: () -> Unit,
    selectionMode: Boolean,
    selectedIds: Set<String>,
) {
    // Folders and files share the same cells: a folder is a sibling of the
    // files beside it, so switching the view re-flows the whole listing.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier.fillMaxSize(),
        // Bottom clearance for the floating glass bar (content flows behind).
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = BottomBarClearance),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ui.folders, key = { "f_${it.id}" }) { folder ->
            FolderTile(
                folder = folder,
                onAction = onFolderAction,
                menuOpen = menuFor == "f_${folder.id}",
                dismissMenu = dismissMenu,
            )
        }
        items(ui.files, key = { "d_${it.id}" }) { file ->
            FileTile(
                file = file,
                thumbs = thumbs,
                onAction = onFileAction,
                menuOpen = menuFor == "d_${file.id}",
                dismissMenu = dismissMenu,
                selectionMode = selectionMode,
                selected = file.id in selectedIds,
            )
        }
    }
}

/**
 * One live file transfer above the listing, read from the transfer centre.
 *
 * A FAILED row carries the retry button. The server keeps every chunk it
 * already stored, so re-running the same PickedFile resume-matches in
 * upload/init and goes straight to complete - without it, resuming was only
 * reachable by picking the file again from scratch.
 */
@Composable
private fun UploadRow(row: TransferItem, onRetry: () -> Unit) {
    val failed = row.phase == TransferPhase.FAILED
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            if (failed) LocalStrings.current.uploadFailedNamed(row.name)
            else LocalStrings.current.uploading(row.name),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = {
                if (row.bytesTotal <= 0L) 0f
                else (row.bytesDone.toDouble() / row.bytesTotal).coerceIn(0.0, 1.0).toFloat()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (failed) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.error ?: LocalStrings.current.uploadFailedRetry,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                // Album batches report one aggregate row and hold no source,
                // so only a single-file row can be re-run from here.
                if (row.retry != null) {
                    TextButton(onClick = onRetry) { Text(LocalStrings.current.actionRetry) }
                }
            }
        }
    }
}

/**
 * Overflow trigger for a grid tile. It hangs off the tile's top-right corner
 * instead of owning a row of its own: as a trailing Column child it made every
 * tile taller than its neighbours and read as an extra caption line, which is
 * what "the three dots took a row" meant.
 *
 * Flat and translucent - no elevation, no shadow - and translucent so it stays
 * legible on top of a photo thumbnail in either theme.
 */
@Composable
private fun TileOverflow(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    menu: @Composable () -> Unit,
) {
    Box(modifier) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = LocalStrings.current.moreActions,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
        }
        menu()
    }
}

/**
 * Folder cell for the grid view. Deliberately the same shape, padding and
 * caption layout as [FileTile]: a folder is a sibling of the files next to it,
 * not a full-width banner above them. Spanning the whole row was why flipping
 * the view button only restyled folders instead of re-flowing them.
 */
@Composable
private fun FolderTile(
    folder: FolderDto,
    onAction: (FolderAction, FolderDto) -> Unit,
    menuOpen: Boolean,
    dismissMenu: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
                MaterialTheme.shapes.medium,
            )
            .combinedClickable(
                onClick = { onAction(FolderAction.OPEN, folder) },
                // Long-press opens the tile's overflow menu, matching the file
                // tiles and the list rows.
                onLongClick = { onAction(FolderAction.TOGGLE_MENU, folder) },
            ),
    ) {
        Column(Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                folder.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Mirrors the file tile's second caption line so both cell types
            // come out the same height; a folder has no byte size of its own.
            Text(
                formatDateTime(folder.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TileOverflow(
            expanded = menuOpen,
            onToggle = { onAction(FolderAction.TOGGLE_MENU, folder) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp),
        ) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = dismissMenu) {
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionRename) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = { onAction(FolderAction.RENAME, folder) },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionShare) },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = { onAction(FolderAction.SHARE, folder) },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionDelete, color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = { onAction(FolderAction.DELETE, folder) },
                )
            }
        }
    }
}

@Composable
private fun FileTile(
    file: FileDto,
    thumbs: ThumbnailLoader,
    onAction: (FileAction, FileDto) -> Unit,
    menuOpen: Boolean,
    dismissMenu: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    // Outer Box hosts the overflow trigger so it can sit on the tile's
    // top-right corner. In the caption row it would have squeezed a
    // three-column tile's name down to a couple of characters.
    Box(
        modifier = Modifier
            // Panel alpha, not an opaque surfaceContainer: a wall of solid
            // cards covered the wallpaper and clashed with the folder cells,
            // which already follow LocalPanelAlpha.
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
                MaterialTheme.shapes.medium,
            )
            .combinedClickable(
                onClick = {
                    if (selectionMode) onAction(FileAction.TOGGLE_SELECT, file)
                    else onAction(FileAction.OPEN, file)
                },
                onLongClick = { onAction(FileAction.TOGGLE_SELECT, file) },
            ),
    ) {
        Column(Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp),
                contentAlignment = Alignment.Center,
            ) {
                FileThumbnail(file, thumbs, edge = 84.dp)
                if (selectionMode) {
                    Icon(
                        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
        }
        // Selection mode owns the corner, browse mode shows the overflow there;
        // they are never both on screen.
        if (!selectionMode) {
            TileOverflow(
                expanded = menuOpen,
                onToggle = { onAction(FileAction.TOGGLE_MENU, file) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
            ) {
                DropdownMenu(expanded = menuOpen, onDismissRequest = dismissMenu) {
                    DropdownMenuItem(
                        text = { Text(LocalStrings.current.actionRename) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { onAction(FileAction.RENAME, file) },
                    )
                    DropdownMenuItem(
                        text = { Text(LocalStrings.current.actionMove) },
                        leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                        onClick = { onAction(FileAction.MOVE, file) },
                    )
                    DropdownMenuItem(
                        text = { Text(LocalStrings.current.actionDownload) },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = { onAction(FileAction.DOWNLOAD, file) },
                    )
                    DropdownMenuItem(
                        text = { Text(LocalStrings.current.actionShare) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = { onAction(FileAction.SHARE, file) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (file.isFavorite) LocalStrings.current.actionUnfavorite
                                else LocalStrings.current.actionFavorite,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (file.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = null,
                            )
                        },
                        onClick = { onAction(FileAction.FAVORITE, file) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (file.archivedAt != null) LocalStrings.current.actionUnarchive
                                else LocalStrings.current.actionArchive,
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                        onClick = { onAction(FileAction.ARCHIVE, file) },
                    )
                    // Only offered for files: a folder has no content of its own to
                    // revision, and the server has no version endpoints for one.
                    DropdownMenuItem(
                        text = { Text(LocalStrings.current.versionHistory) },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                        onClick = { onAction(FileAction.VERSIONS, file) },
                    )
                    DropdownMenuItem(
                        text = { Text(LocalStrings.current.actionDelete, color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { onAction(FileAction.DELETE, file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    folder: FolderDto,
    onAction: (FolderAction, FolderDto) -> Unit,
    menuOpen: Boolean,
    dismissMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Long-press opens the same menu as the 3-dot button, matching the
            // file rows; folders previously had no long-press at all.
            .combinedClickable(
                onClick = { onAction(FolderAction.OPEN, folder) },
                onLongClick = { onAction(FolderAction.TOGGLE_MENU, folder) },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(folder.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            IconButton(onClick = { onAction(FolderAction.TOGGLE_MENU, folder) }) {
                Icon(Icons.Default.MoreVert, contentDescription = LocalStrings.current.moreActions)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = dismissMenu) {
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionRename) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = { onAction(FolderAction.RENAME, folder) },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionShare) },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = { onAction(FolderAction.SHARE, folder) },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionDelete, color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = { onAction(FolderAction.DELETE, folder) },
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    file: FileDto,
    thumbs: ThumbnailLoader,
    onAction: (FileAction, FileDto) -> Unit,
    menuOpen: Boolean,
    dismissMenu: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    // Desktop has no long-press, so the checkbox shows on hover as well: it is
    // the only way a mouse user can start a multi-select here.
    var hovered by remember(file.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onHoverChanged { hovered = it }
            .combinedClickable(
                onClick = {
                    if (selectionMode) onAction(FileAction.TOGGLE_SELECT, file)
                    else onAction(FileAction.OPEN, file)
                },
                onLongClick = { onAction(FileAction.TOGGLE_SELECT, file) },
            )
            // 8dp vertical: 40dp thumbnail + two text lines + 1dp divider comes
            // to 64dp, the top of the 56-64dp row band. At 10dp the row
            // measured 68dp and the whole column read loose on a desktop width.
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!selectionMode && hovered) {
            IconButton(onClick = { onAction(FileAction.TOGGLE_SELECT, file) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.RadioButtonUnchecked,
                    contentDescription = LocalStrings.current.actionSelect,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            FileThumbnail(file, thumbs, edge = 40.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatFileSize(file.size)} · ${formatDateTime(file.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selectionMode) {
            // The circle where the 3-dot used to be.
            IconButton(onClick = { onAction(FileAction.TOGGLE_SELECT, file) }) {
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
        Box {
            IconButton(onClick = { onAction(FileAction.TOGGLE_MENU, file) }) {
                Icon(Icons.Default.MoreVert, contentDescription = LocalStrings.current.moreActions)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = dismissMenu) {
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionRename) },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = { onAction(FileAction.RENAME, file) },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionMove) },
                    leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                    onClick = { onAction(FileAction.MOVE, file) },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionDownload) },
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                    onClick = { onAction(FileAction.DOWNLOAD, file) },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionShare) },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = { onAction(FileAction.SHARE, file) },
                )
                // Flag actions reflect the current state so the menu reads as the
                // action it performs, matching the album selection bar wording.
                DropdownMenuItem(
                    text = {
                        Text(
                            if (file.isFavorite) LocalStrings.current.actionUnfavorite
                            else LocalStrings.current.actionFavorite,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (file.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                        )
                    },
                    onClick = { onAction(FileAction.FAVORITE, file) },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (file.archivedAt != null) LocalStrings.current.actionUnarchive
                            else LocalStrings.current.actionArchive,
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                    onClick = { onAction(FileAction.ARCHIVE, file) },
                )
                // Only offered for files: a folder has no content of its own to
                // revision, and the server has no version endpoints for one.
                // Multi-select entry that works with a mouse: long-press is a
                // touch gesture and the row checkbox only appears on hover,
                // so without this the feature is unreachable on desktop.
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionSelect) },
                    leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                    onClick = { onAction(FileAction.TOGGLE_SELECT, file) },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.versionHistory) },
                    leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                    onClick = { onAction(FileAction.VERSIONS, file) },
                )
                DropdownMenuItem(
                    text = { Text(LocalStrings.current.actionDelete, color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = { onAction(FileAction.DELETE, file) },
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
        containerColor = MaterialTheme.colorScheme.surface,
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
    // Drill-down picker like the upload destination dialog: tapping a folder
    // descends into it, and "move here" targets the folder currently on
    // display. The old flat root-level list could never reach nested
    // destinations (e.g. album category folders).
    // Selection uses sentinel ROOT_FOLDER_ID for the root directory.
    var stack by remember { mutableStateOf(listOf<FolderDto>()) }
    var entries by remember { mutableStateOf<List<FolderDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    val currentId = stack.lastOrNull()?.id ?: ROOT_FOLDER_ID

    LaunchedEffect(stack, retry) {
        loading = true
        error = null
        repo.contents(currentId).fold(
            onSuccess = { entries = it.folders; loading = false },
            onFailure = { e ->
                entries = emptyList()
                error = e.message?.takeIf { it.isNotBlank() } ?: I18n.strings.loadFailed
                loading = false
            },
        )
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
        title = { Text(LocalStrings.current.moveToTitle(target.fileName)) },
        text = {
            Box(modifier = Modifier.height(280.dp)) {
                Column(Modifier.fillMaxSize()) {
                    if (stack.isNotEmpty()) {
                        TextButton(onClick = { stack = stack.dropLast(1) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stack.dropLast(1).lastOrNull()?.name ?: LocalStrings.current.rootFolder)
                        }
                    }
                    when {
                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        error != null -> Column {
                            Text(error!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { retry++ }) { Text(LocalStrings.current.actionRetry) }
                        }
                        entries.isEmpty() -> Text(
                            LocalStrings.current.folderEmpty,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                        )
                        else -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            entries.forEach { f ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { stack = stack + f }
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
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                // Move into the folder on display; disabled on load errors and
                // when it is the file's current folder (a no-op move).
                enabled = !loading && error == null &&
                    currentId != (target.fromFolderId ?: ROOT_FOLDER_ID),
                onClick = { onConfirm(currentId) },
            ) {
                Text(LocalStrings.current.moveHere)
            }
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
    /** A link was created: the caller owns the toast (this dialog has no host). */
    onCreated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var existing by remember { mutableStateOf<List<ShareDto>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var ttlIndex by remember { mutableStateOf(0) }
    var freshUrl by remember { mutableStateOf<String?>(null) }
    // Id of the share the freshUrl banner refers to. Revoking must hit exactly
    // the just-created link: falling back to existing.firstOrNull() revoked the
    // oldest link whenever this file already had one, leaving the new link live.
    var freshId by remember { mutableStateOf<String?>(null) }
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
        containerColor = MaterialTheme.colorScheme.surface,
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
                                        freshId?.let { id ->
                                            repo.revokeShare(id).fold(
                                                onSuccess = { freshUrl = null; freshId = null; refresh() },
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
                                        freshId = share.id
                                        copied = false
                                        creating = false
                                        refresh()
                                        // The link is only visible inside this
                                        // dialog; once it is closed there was no
                                        // sign the link had been made at all.
                                        onCreated()
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
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    val currentId = stack.lastOrNull()?.id

    LaunchedEffect(stack, retry) {
        loading = true
        error = null
        repo.contents(currentId ?: "root").fold(
            onSuccess = { entries = it.folders },
            // A failed listing must read as an error, not as "no folders":
            // confirming would otherwise upload into a folder the user never
            // saw, silently.
            onFailure = { e ->
                entries = emptyList()
                error = e.message?.takeIf { it.isNotBlank() } ?: I18n.strings.loadFailed
            },
        )
        loading = false
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
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
                val err = error
                if (loading) {
                    Text(LocalStrings.current.loading, style = MaterialTheme.typography.bodySmall)
                } else if (err != null) {
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { retry++ }) { Text(LocalStrings.current.actionRetry) }
                }
                if (!loading && err == null) {
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
            }
        },
        confirmButton = {
            val rootLabel = LocalStrings.current.rootFolder
            TextButton(
                enabled = error == null,
                onClick = {
                    onPicked(currentId, stack.lastOrNull()?.name ?: rootLabel)
                },
            ) { Text(LocalStrings.current.actionConfirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(LocalStrings.current.actionCancel) }
        },
    )
}
