package com.linan.barezen_drive.ui.screens.album

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.ViewAgenda
import com.linan.barezen_drive.ui.component.AlbumTransferEntryIcon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.data.upload.UploadManager
import com.linan.barezen_drive.platform.PickedFile
import com.linan.barezen_drive.platform.rememberImagePicker
import com.linan.barezen_drive.platform.monotonicNowMs
import com.linan.barezen_drive.platform.deviceName
import com.linan.barezen_drive.platform.legacyDeviceName
import com.linan.barezen_drive.data.repo.AlbumFolder
import com.linan.barezen_drive.ui.media.ThumbnailLoader
import com.linan.barezen_drive.ui.media.fileIcon
import com.linan.barezen_drive.ui.media.formatDateTime
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.ui.shell.BottomBarClearance
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.platform.copyToClipboard
import com.linan.barezen_drive.platform.rememberFileSaver
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.text.style.TextOverflow

private const val PAGE_SIZE = 200

/** One local calendar day of the timeline. */
private data class DaySection(val day: String, val files: List<FileDto>)

private data class CollectionTile(val name: String, val folderId: String, val cover: FileDto?)

/** Album display time: capture time when the device provided it, else upload time. */
internal fun albumTime(file: FileDto): String = file.takenAt ?: file.updatedAt

/**
 * Buckets the timeline into local calendar days, newest first. Both timeline
 * layouts section on this: which day a photo belongs to is a client-side concern
 * (device timezone), and the label itself is resolved at the call site so each
 * layout can spell out "today" / "yesterday" in the active language.
 */
private fun daySections(files: List<FileDto>): List<DaySection> =
    files.groupBy { formatDateTime(albumTime(it)).take(10) }
        .map { (day, fs) -> DaySection(day, fs) }

/**
 * Photo timeline: all images of the account, newest first, bucketed into
 * month sections (for example "September 2026" in the active language) in the
 * device timezone. The waterfall grid
 * keeps original aspect ratios; tapping opens the swipeable preview across
 * every loaded photo. Pages load on demand as the bottom becomes visible.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumScreen(
    repo: FilesRepository,
    thumbs: ThumbnailLoader,
    uploader: UploadManager,
    onBack: (() -> Unit)?,
    onPreview: (List<FileDto>, Int, Boolean) -> Unit,
    onOpenUploads: () -> Unit = {},
    avatar: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbar = SnackbarHostState()
    // Loaded pages of the timeline, newest first. Kept as one list rather than
    // derived on demand because the load-more footer keys its request on this
    // instance: a new page has to produce a new list, a recomposition must not.
    var loaded by remember { mutableStateOf<List<FileDto>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var exhausted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped whenever the page list is discarded (scope/category switch,
    // refresh): lets an in-flight page from the OLD scope be dropped instead of
    // leaking into the new one and leaving the fresh load swallowed by the
    // loading flag.
    var generation by remember { mutableStateOf(0) }
    // Bumped on a scope switch that lands on the SAME folder id, which the
    // key-based reload effect below would otherwise not notice.
    var reloadTick by remember { mutableStateOf(0) }
    var pendingUploads by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var uploadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // The progress dialog is a shortcut view, not a gate: dismissing it must not
    // touch the upload, which is owned by the LaunchedEffect below and mirrored
    // into the transfer centre. A new batch re-opens it.
    var uploadDialogVisible by remember { mutableStateOf(true) }
    val progress by uploader.progress.collectAsState()
    // This device's own album folder: uploads always target it. Kept apart
    // from albumFolderId (the currently *browsed* scope: a device folder, a
    // category, or the album root for "all devices"), so switching the
    // browsing scope can never redirect uploads into another device's folder.
    var deviceFolderId by remember { mutableStateOf<String?>(null) }
    var albumFolderId by remember { mutableStateOf<String?>(null) }
    // Album-folder resolution can fail while the server is unreachable.
    // Without a visible retry the screen just sits on "no photos" forever.
    var resolveFailed by remember { mutableStateOf(false) }
    var resolveTick by remember { mutableStateOf(0) }
    val device = remember { deviceName() }

    var scopeName by remember { mutableStateOf(device) }
    var deviceOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var deviceMenuOpen by remember { mutableStateOf(false) }
    // Category tabs mirror the real subfolders of the selected device folder
    // (they exist only because an upload created them). Null = all media of
    // the whole device subtree; when browsing "all devices" there are no
    // categories, since scopes differ per device.
    var categories by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var activeCategory by remember { mutableStateOf<String?>(null) }
    // False = collections landing (rounded first-photo covers per phone album);
    // true = the photo grid of the whole device subtree or one category.
    var timelineMode by remember { mutableStateOf(false) }
    val prefs = com.linan.barezen_drive.data.local.AppPreferences.get()
    var viewMode by remember { mutableStateOf(prefs.albumViewMode) }
    // Favorites filter: when on, the timeline loads only starred photos (the
    // server narrows the same scoped query with favorite=true). Kept apart from
    // the browsing scope so switching device/category resets it back to "all".
    var favoriteOnly by remember { mutableStateOf(false) }
    // Pinch-to-zoom column count for the timeline grids (2-6, persisted). Both
    // the uniform grid and the waterfall read it, so the density carries over
    // when the view mode is switched.
    var columns by remember { mutableStateOf(prefs.albumColumns.coerceIn(2, 6)) }
    var zoomAccum by remember { mutableStateOf(1f) }
    // Grid scroll state for the uniform layout, so a drag-sweep can hit-test the
    // visible cells under the finger and paint them into the selection.
    val gridState = rememberLazyGridState()
    // Staggered-grid scroll state for the waterfall. It cannot host a sticky
    // header, so its day label is an overlay derived from the first visible item.
    val staggeredState = rememberLazyStaggeredGridState()
    fun setColumnCount(n: Int) {
        val c = n.coerceIn(2, 6)
        if (c != columns) {
            columns = c
            prefs.albumColumns = c
        }
    }
    // Accumulate the per-event distance ratio and snap to a column step once the
    // gesture has clearly opened (fewer, bigger) or closed (more, smaller).
    fun onPinch(scale: Float) {
        zoomAccum *= scale
        if (zoomAccum >= 1.3f) { setColumnCount(columns - 1); zoomAccum = 1f }
        else if (zoomAccum <= 0.77f) { setColumnCount(columns + 1); zoomAccum = 1f }
    }
    // Google-Photos selection: long-press a tile to enter, tap toggles, a
    // bottom bar carries the actions until the selection is cleared.
    val selected = remember { mutableStateMapOf<String, FileDto>() }
    val selectionMode = selected.isNotEmpty()
    var collections by remember { mutableStateOf<List<CollectionTile>>(emptyList()) }
    var collectionsLoading by remember { mutableStateOf(false) }
    val allPhotosLabel = LocalStrings.current.allPhotos
    val allDevicesLabel = LocalStrings.current.allDevices

    LaunchedEffect(resolveTick) {
        val id = AlbumFolder.resolve(repo, device, legacyDeviceName())
        resolveFailed = id == null
        deviceFolderId = id
        // First success (or success after a failed start) also opens the
        // default browsing scope; later retries must not yank the user out
        // of a scope they had switched to.
        if (id != null && albumFolderId == null) albumFolderId = id
        deviceOptions = AlbumFolder.listDevices(repo)
        id?.let { categories = repo.contents(it).getOrNull()?.folders?.map { f -> f.name to f.id } ?: emptyList() }
    }

    // Covers for the collections landing: the newest photo of each folder.
    LaunchedEffect(albumFolderId, categories) {
        val scope = albumFolderId ?: return@LaunchedEffect
        if (timelineMode || scopeName == allDevicesLabel) return@LaunchedEffect
        collectionsLoading = true
        suspend fun coverOf(folderId: String?): FileDto? =
            repo.album(1, null, folderId).getOrNull()?.files?.firstOrNull()
        val tiles = mutableListOf(CollectionTile(allPhotosLabel, scope, coverOf(scope)))
        for ((name, id) in categories) {
            tiles.add(CollectionTile(name, id, coverOf(id)))
        }
        collections = tiles
        collectionsLoading = false
    }

    fun switchScope(id: String?, name: String) {
        generation++
        loading = false
        loaded = emptyList(); cursor = null; exhausted = false; error = null
        activeCategory = null
        favoriteOnly = false
        timelineMode = false
        scopeName = name
        categories = emptyList()
        scope.launch {
            categories = id?.let { cid ->
                if (name == allDevicesLabel) emptyList()
                else repo.contents(cid).getOrNull()?.folders?.map { f -> f.name to f.id } ?: emptyList()
            } ?: emptyList()
        }
        // The reload effect picks this up (tick makes same-scope reselects reload too).
        albumFolderId = id
        reloadTick++
    }

    // Upload speed: derived from consecutive progress callbacks, sampled at
    // most once per 500 ms.
    var speedText by remember { mutableStateOf("…") }
    LaunchedEffect(pendingUploads) {
        if (pendingUploads.isEmpty()) return@LaunchedEffect
        var lastAt = monotonicNowMs()
        var lastBytes = 0L
        uploader.progress.collect { p ->
            val now = monotonicNowMs()
            val dt = now - lastAt
            if (dt >= 500) {
                val rate = ((p.bytesDone - lastBytes).coerceAtLeast(0) / (dt / 1000.0)).toLong()
                speedText = if (rate >= 0) com.linan.barezen_drive.ui.screens.files.formatFileSize(rate) + "/s" else "…"
                lastAt = now
                lastBytes = p.bytesDone
            }
        }
    }

    val imagePicker = rememberImagePicker { picks ->
        if (picks.isNotEmpty()) pendingUploads = picks
    }

    fun loadMore() {
        if (loading || exhausted) return
        val myGen = generation
        scope.launch {
            loading = true
            repo.album(PAGE_SIZE, cursor, activeCategory ?: albumFolderId, favorite = favoriteOnly).fold(
                onSuccess = { page ->
                    // Page answering a discarded request (the user switched
                    // device/category in the meantime): drop it, do not touch
                    // the fresh scope's flags or append into its list.
                    if (myGen == generation) {
                        loading = false
                        loaded = loaded + page.files
                        cursor = page.nextCursor
                        if (page.nextCursor == null) exhausted = true
                        error = null
                    }
                },
                onFailure = {
                    if (myGen == generation) {
                        loading = false
                        error = it.message?.takeIf { m -> m.isNotBlank() } ?: I18n.strings.loadFailed
                    }
                },
            )
        }
    }

    LaunchedEffect(albumFolderId, reloadTick) { if (albumFolderId != null) loadMore() }

    // Upload the picked photos sequentially into THIS DEVICE's album folder;
    // cancelling the current one aborts its session and moves to the next.
    LaunchedEffect(pendingUploads) {
        if (pendingUploads.isEmpty()) return@LaunchedEffect
        val target = deviceFolderId
            ?: AlbumFolder.resolve(repo, device, legacyDeviceName()).also { deviceFolderId = it }
        if (target == null) {
            // Offline album resolution must not silently drop photos into the
            // general file tree (folderId null): fail the batch visibly.
            pendingUploads = emptyList()
            snackbar.showSnackbar(I18n.strings.uploadFailedRetry)
            return@LaunchedEffect
        }
        val picks = pendingUploads
        uploadDialogVisible = true
        picks.forEach { picked ->
            // Photos carry their phone-album name; the matching category
            // folder is created on first upload (null = straight into the
            // device folder, e.g. web/desktop uploads).
            val perFile = picked.originAlbum?.takeIf { it.isNotBlank() }
                ?.let { cat -> AlbumFolder.resolveCategory(repo, target, cat) }
                ?: target
            // Album-initiated uploads list on the album transfer page, not
            // the file one: the lane split is by where the work belongs.
            uploadJob = launch {
                uploader.upload(picked, perFile, lane = com.linan.barezen_drive.data.transfer.TransferLane.ALBUM)
            }
            uploadJob?.join()
        }
        uploadJob = null
        pendingUploads = emptyList()
        // Refresh the grid so new photos appear immediately.
        generation++
        loading = false
        loaded = emptyList()
        cursor = null
        exhausted = false
        loadMore()
    }


    val saver = rememberFileSaver { result ->
        if (result == null) scope.launch { snackbar.showSnackbar(I18n.strings.downloadFailed) }
    }
    var shareUrl by remember { mutableStateOf<String?>(null) }
    // Every photo currently loaded across pages; powers "select all" and the
    // drag-sweep hit-test.
    val loadedFiles = loaded
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
        // Lift the snackbar above the floating bottom bar: at scaffold
        // bottom it sits behind the translucent glass and reads as a
        // second, stacked navigation bar (seen on connection errors).
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = BottomBarClearance)) },
        bottomBar = {
            if (selectionMode) {
                // Google-Photos action bar: favorite / archive / share / download /
                // delete for the whole selection; the tiles carry the checkmarks.
                // A floating pill lifted above the glass bottom bar: docked at the
                // scaffold bottom it landed exactly under that bar, untappable.
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = BottomBarClearance),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        BarAction(Icons.Default.FavoriteBorder, LocalStrings.current.actionFavorite) {
                            scope.launch {
                                // Favoriting keeps every file visible where it is, so
                                // no reload: only the favorites filter needs a refresh
                                // next time it is opened.
                                val failed = selected.values.count { repo.setFavorite(it.id, true).isFailure }
                                selected.clear()
                                snackbar.showSnackbar(
                                    if (failed > 0) I18n.strings.operationFailed
                                    else I18n.strings.favorited,
                                )
                            }
                        }
                        BarAction(Icons.Default.Archive, LocalStrings.current.actionArchive) {
                            scope.launch {
                                val failed = selected.values.count { repo.setArchived(it.id, true).isFailure }
                                val hadSelection = selected.isNotEmpty()
                                selected.clear()
                                // Archiving removes the rows from the timeline, so the
                                // grid must reload to drop them.
                                if (hadSelection) {
                                    generation++
                                    loading = false
                                    loaded = emptyList(); cursor = null; exhausted = false; error = null
                                    loadMore()
                                }
                                snackbar.showSnackbar(
                                    if (failed > 0) I18n.strings.operationFailed
                                    else I18n.strings.archived,
                                )
                            }
                        }
                        BarAction(Icons.Default.Share, LocalStrings.current.actionShare) {
                            scope.launch {
                                selected.values.firstOrNull()?.let { first ->
                                    repo.createShare(fileId = first.id).fold(
                                        onSuccess = { shareUrl = it.url },
                                        onFailure = { snackbar.showSnackbar(I18n.strings.operationFailed) },
                                    )
                                }
                            }
                        }
                        BarAction(Icons.Default.Download, LocalStrings.current.actionDownload) {
                            selected.values.forEach { file ->
                                saver(file.name, file.mimeType) {
                                    repo.download(file.id)
                                }
                            }
                            selected.clear()
                        }
                        BarAction(Icons.Default.Delete, LocalStrings.current.actionDelete, tint = MaterialTheme.colorScheme.error) {
                            scope.launch {
                                val failed = selected.values.count { repo.deleteFile(it.id).isFailure }
                                val hadSelection = selected.isNotEmpty()
                                selected.clear()
                                if (hadSelection) {
                                    generation++
                                    loading = false
                                    loaded = emptyList(); cursor = null; exhausted = false; error = null
                                    loadMore()
                                }
                                // Plain delete is a soft delete: say so, so the user
                                // knows the recovery path exists.
                                snackbar.showSnackbar(
                                    if (failed > 0) I18n.strings.deleteFailed
                                    else I18n.strings.movedToTrash,
                                )
                            }
                        }
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                // Transparent like the home tab: the default opaque bar is a
                // white strip over the wallpaper.
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    if (selectionMode) {
                        Text(
                            LocalStrings.current.selectedCount(selected.size),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        return@TopAppBar
                    }
                    // Device switcher: defaults to this device; lists every
                    // device that has actually uploaded, plus an all-devices
                    // view over the whole album tree.
                    Box {
                        TextButton(onClick = { deviceMenuOpen = true }) {
                            Text(scopeName, style = MaterialTheme.typography.titleMedium)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = deviceMenuOpen,
                            onDismissRequest = { deviceMenuOpen = false },
                        ) {
                            deviceOptions.forEach { (name, id) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        deviceMenuOpen = false
                                        scope.launch { switchScope(id, name) }
                                    },
                                )
                            }
                            // "All devices": the whole album subtree, with no
                            // collections landing (categories differ per device).
                            DropdownMenuItem(
                                text = { Text(allDevicesLabel) },
                                onClick = {
                                    deviceMenuOpen = false
                                    scope.launch {
                                        val root = AlbumFolder.rootId(repo)
                                        if (root != null) switchScope(root, allDevicesLabel)
                                        else snackbar.showSnackbar(I18n.strings.loadFailed)
                                    }
                                },
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { selected.clear() }) {
                            Icon(Icons.Default.Close, contentDescription = LocalStrings.current.actionCancel)
                        }
                    } else if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LocalStrings.current.actionBack)
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        // Google-Photos "select all": grab every loaded photo;
                        // tapping again clears the whole selection.
                        IconButton(onClick = {
                            if (selected.size >= loadedFiles.size) selected.clear()
                            else loadedFiles.forEach { selected[it.id] = it }
                        }) {
                            Icon(
                                Icons.Default.SelectAll,
                                contentDescription = LocalStrings.current.selectAll,
                            )
                        }
                    } else if (timelineMode) {
                        // Favorites filter: show only starred photos of the
                        // current scope; tapping again returns to the timeline.
                        IconButton(onClick = {
                            favoriteOnly = !favoriteOnly
                            generation++
                            loading = false
                            loaded = emptyList(); cursor = null; exhausted = false; error = null
                            loadMore()
                        }) {
                            Icon(
                                if (favoriteOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (favoriteOnly) LocalStrings.current.actionUnfavorite else LocalStrings.current.actionFavorite,
                                tint = if (favoriteOnly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        // Cycle waterfall -> uniform -> by date; persisted.
                        IconButton(onClick = {
                            viewMode = (viewMode + 1) % 3
                            prefs.albumViewMode = viewMode
                        }) {
                            Icon(
                                when (viewMode) {
                                    1 -> Icons.Default.GridOn
                                    2 -> Icons.Default.CalendarViewDay
                                    else -> Icons.Default.ViewAgenda
                                },
                                contentDescription = when (viewMode) {
                                    1 -> LocalStrings.current.viewUniform
                                    2 -> LocalStrings.current.viewDated
                                    else -> LocalStrings.current.viewWaterfall
                                },
                            )
                        }
                    }
                    IconButton(onClick = { imagePicker() }) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = LocalStrings.current.pickFromGallery)
                    }
                    // Album transfer entry: sync batches and album picks,
                    // kept apart from the file transfer page.
                    AlbumTransferEntryIcon(onClick = onOpenUploads)
                    avatar()
                },
            )
        },
    ) { pad ->
        val flat = loaded
        // Day sections drive both timeline layouts, and the label resolver turns
        // a section key into "today" / "yesterday" or the bare date.
        val sections = remember(flat) { daySections(flat) }
        val strings = LocalStrings.current
        val todayStr = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
        val yesterdayStr = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            .date.minus(1, DateTimeUnit.DAY).toString()
        fun dayLabel(day: String): String = when (day) {
            todayStr -> strings.today
            yesterdayStr -> strings.yesterday
            else -> day
        }
        when {
            // Album folder unreachable: offer a retry instead of a permanent
            // empty screen.
            resolveFailed -> Centered(pad) {
                Text(LocalStrings.current.loadFailed, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { resolveTick++ }) { Text(LocalStrings.current.actionRetry) }
            }
            // Collections landing: one rounded cover per phone album (the
            // first photo), plus an all-photos tile - Google Photos style.
            !timelineMode && scopeName != allDevicesLabel -> Centered(pad) {
                if (collectionsLoading) {
                    CircularProgressIndicator()
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        // Bottom clears the floating glass bar, like files/home.
                        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = BottomBarClearance),
                    ) {
                        items(collections.size, key = { collections[it].folderId }) { i ->
                            val tile = collections[i]
                            CollectionCoverTile(tile = tile, thumbs = thumbs, onClick = {
                                timelineMode = true
                                // The "all photos" tile keeps activeCategory
                                // null; a category tile scopes the load. Always
                                // reset the page list, otherwise a previous
                                // category's photos leak into this view.
                                activeCategory = if (tile.folderId != albumFolderId) tile.folderId else null
                                generation++
                                loading = false
                                loaded = emptyList(); cursor = null; exhausted = false; error = null
                                loadMore()
                            })
                        }
                    }
                }
            }
            // Initial load failed (pages already on screen report through the
            // grid footer instead).
            error != null && loaded.isEmpty() -> Centered(pad) {
                Text(error ?: LocalStrings.current.loadFailed, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { error = null; loadMore() }) { Text(LocalStrings.current.actionRetry) }
            }
            loaded.isEmpty() && loading -> Centered(pad) { CircularProgressIndicator() }
            loaded.isEmpty() -> Centered(pad) {
                Text(LocalStrings.current.noPhotos, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (timelineMode) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { timelineMode = false }) {
                        Text(LocalStrings.current.backToCollections)
                    }
                }
            }
            // Dated layout: day sections of uniform squares.
            timelineMode && viewMode == 2 -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .pinchToColumnCount(
                        onPinchStart = { zoomAccum = 1f },
                        onScale = { onPinch(it) },
                    ),
                contentPadding = PaddingValues(bottom = BottomBarClearance),
            ) {
                item(key = "back") { BackToCollectionsChip { timelineMode = false } }
                sections.forEach { section ->
                    stickyHeader(key = "d_${section.day}") {
                        Text(
                            dayLabel(section.day),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Same translucency as the screen panel: an
                                // opaque strip here reads as a white bar over
                                // the wallpaper.
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(section.files.chunked(columns).size) { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                            val rowFiles = section.files.chunked(columns)[row]
                            rowFiles.forEach { file ->
                                Box(Modifier.weight(1f).padding(2.dp)) {
                                    AlbumTile(
                                        file = file,
                                        thumbs = thumbs,
                                        square = true,
                                        inSelection = selectionMode,
                                        isSelected = file.id in selected,
                                        onClick = {
                                            if (selectionMode) {
                                                if (file.id in selected) selected.remove(file.id) else selected[file.id] = file
                                            } else {
                                                val index = flat.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
                                                onPreview(flat, index, true)
                                            }
                                        },
                                        onLongClick = {
                                            selected[file.id] = file
                                        },
                                    )
                                }
                            }
                            repeat(columns - rowFiles.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                item(key = "loading") {
                    AlbumLoadMoreFooter(
                        pageToken = loaded,
                        error = error,
                        loading = loading,
                        onRequest = { loadMore() },
                        onRetry = { error = null; loadMore() },
                    )
                }
            }
            // Uniform grid: fixed square cells.
            timelineMode && viewMode == 1 -> Column(Modifier.fillMaxSize().padding(pad)) {
                BackToCollectionsChip { timelineMode = false }
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier
                        .fillMaxSize()
                        .dragSweepSelect(active = selectionMode) { pos ->
                            val index = gridState.itemIndexAt(pos) ?: return@dragSweepSelect
                            loadedFiles.getOrNull(index)?.let { if (it.id !in selected) selected[it.id] = it }
                        }
                        .pinchToColumnCount(
                            onPinchStart = { zoomAccum = 1f },
                            onScale = { onPinch(it) },
                        ),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = BottomBarClearance),
                ) {
                    items(flat.size, key = { flat[it].id }) { i ->
                        AlbumTile(
                            file = flat[i],
                            thumbs = thumbs,
                            square = true,
                            inSelection = selectionMode,
                            isSelected = flat[i].id in selected,
                            onClick = {
                                if (selectionMode) {
                                    if (flat[i].id in selected) selected.remove(flat[i].id) else selected[flat[i].id] = flat[i]
                                } else {
                                    onPreview(flat, i, true)
                                }
                            },
                            onLongClick = { selected[flat[i].id] = flat[i] },
                        )
                    }
                    if (!exhausted) {
                        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                            AlbumLoadMoreFooter(
                                pageToken = loaded,
                                error = error,
                                loading = loading,
                                onRequest = { loadMore() },
                                onRetry = { error = null; loadMore() },
                            )
                        }
                    }
                }
            }
            // Waterfall: intrinsic tile shapes, day sections, and - because a
            // staggered grid cannot host a sticky header - the day label is drawn
            // as an overlay that follows the first visible item.
            else -> Box(Modifier.fillMaxSize().padding(pad)) {
                LazyVerticalStaggeredGrid(
                    state = staggeredState,
                    columns = StaggeredGridCells.Fixed(columns),
                    modifier = Modifier
                        .fillMaxSize()
                        .pinchToColumnCount(
                            onPinchStart = { zoomAccum = 1f },
                            onScale = { onPinch(it) },
                        ),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalItemSpacing = 4.dp,
                    contentPadding = PaddingValues(bottom = BottomBarClearance),
                ) {
                    sections.forEach { section ->
                        item(key = "h_${section.day}", span = StaggeredGridItemSpan.FullLine) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(dayLabel(section.day), style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "${section.files.size}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                        items(section.files, key = { it.id }) { file ->
                            AlbumTile(
                                file = file,
                                thumbs = thumbs,
                                inSelection = selectionMode,
                                isSelected = file.id in selected,
                                onClick = {
                                    if (selectionMode) {
                                        if (file.id in selected) selected.remove(file.id) else selected[file.id] = file
                                    } else {
                                        val index = flat.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
                                        onPreview(flat, index, true)
                                    }
                                },
                                onLongClick = { selected[file.id] = file },
                            )
                        }
                    }
                    if (!exhausted) {
                        item(key = "loading", span = StaggeredGridItemSpan.FullLine) {
                            AlbumLoadMoreFooter(
                                pageToken = loaded,
                                error = error,
                                loading = loading,
                                onRequest = { loadMore() },
                                onRetry = { error = null; loadMore() },
                            )
                        }
                    }
                }
                // The overlay is suppressed while the section's own in-flow header
                // is still the first thing on screen, otherwise the label prints
                // twice; anywhere further down, the overlay is what keeps the day
                // visible. Each section occupies one full-line header followed by
                // its tiles, which is what these two index tables describe.
                val sectionOfItem = remember(sections) {
                    buildList { sections.forEachIndexed { si, s -> repeat(s.files.size + 1) { add(si) } } }
                }
                val headerItemOfSection = remember(sections) {
                    var i = 0
                    sections.map { s -> i.also { i += s.files.size + 1 } }
                }
                val firstItem = staggeredState.firstVisibleItemIndex
                val sectionOfFirst = sectionOfItem.getOrNull(firstItem)
                if (sectionOfFirst != null && headerItemOfSection[sectionOfFirst] != firstItem) {
                    Text(
                        dayLabel(sections[sectionOfFirst].day),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            // Track the panel alpha, or the floating day label
                            // is a white bar over the wallpaper.
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        shareUrl?.let { url ->
            AlertDialog(
                onDismissRequest = { shareUrl = null },
                title = { Text(LocalStrings.current.actionShare) },
                text = { Text(url) },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            copyToClipboard(url)
                            shareUrl = null
                        }
                    }) { Text(LocalStrings.current.copied) }
                },
                dismissButton = {
                    TextButton(onClick = { shareUrl = null }) { Text(LocalStrings.current.actionCancel) }
                },
            )
        }

        // Upload progress dialog: progress, speed and cancel. Dismissing only
        // hides the shortcut - the transfer centre keeps the row and its stop
        // button, so the batch is never trapped behind a dialog the user cannot
        // close.
        if (pendingUploads.isNotEmpty() && uploadDialogVisible) {
            val p = progress
            AlertDialog(
                onDismissRequest = { uploadDialogVisible = false },
                title = { Text(LocalStrings.current.uploadingGeneric) },
                text = {
                    Column {
                        Text(
                            p.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { p.fraction.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${(p.fraction * 100).toInt()}% · $speedText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { uploadDialogVisible = false }) {
                        Text(LocalStrings.current.uploadRunInBackground)
                    }
                },
                confirmButton = {
                    // Cancels the file in flight; the batch then moves on to the
                    // next pick (see the LaunchedEffect above).
                    TextButton(onClick = { uploadJob?.cancel() }) { Text(LocalStrings.current.actionCancel) }
                },
            )
        }
    }
}

@Composable
private fun Centered(pad: PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/**
 * Timeline footer shared by the three grid modes: fetches the next page
 * whenever it becomes visible (keyed on the page list identity), and swaps
 * the spinner for an inline retry when the last page failed.
 */
@Composable
private fun AlbumLoadMoreFooter(
    pageToken: Any,
    error: String?,
    loading: Boolean,
    onRequest: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LaunchedEffect(pageToken) { onRequest() }
        val err = error
        if (err != null) {
            Text(err, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRetry) { Text(LocalStrings.current.actionRetry) }
        } else if (loading) {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
    }
}

/** Waterfall tile: loads the cover, then occupies its intrinsic aspect ratio. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTile(
    file: FileDto,
    thumbs: ThumbnailLoader,
    onClick: () -> Unit,
    square: Boolean = false,
    inSelection: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    var bitmap by remember(file.id) { mutableStateOf<ImageBitmap?>(null) }
    // Decoupled from bitmap: load() returning null means "no cover" (not still
    // loading), so the tile settles on the type-icon placeholder instead of
    // spinning forever like it used to.
    var coverLoaded by remember(file.id) { mutableStateOf(false) }
    LaunchedEffect(file.id) {
        bitmap = thumbs.load(file.id)
        coverLoaded = true
    }
    Box(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        val bmp = bitmap
        when {
            bmp != null && bmp.width > 0 && bmp.height > 0 -> Image(
                bitmap = bmp,
                contentDescription = file.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (square) 1f else bmp.width.toFloat() / bmp.height),
                contentScale = ContentScale.Crop,
            )
            else -> Box(
                Modifier
                    .fillMaxWidth()
                    .let { if (square) it.aspectRatio(1f) else it }
                    .height(110.dp).background(
                    MaterialTheme.colorScheme.surfaceVariant,
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (coverLoaded) {
                    Icon(
                        fileIcon(file.mimeType),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (inSelection) {
            // Google-Photos checkmark: filled when picked, hollow circle when
            // selection mode is on but this tile is not picked.
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(24.dp)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                        CircleShape,
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}


/**
 * Collections landing tile: the newest photo of a phone album as a rounded
 * cover, with the album name underneath - like the collections tab of a
 * phone gallery. Tapping opens that album's photo grid.
 */
@Composable
private fun CollectionCoverTile(
    tile: CollectionTile,
    thumbs: ThumbnailLoader,
    onClick: () -> Unit,
) {
    Column(Modifier.clickable(onClick = onClick)) {
        var bitmap by remember(tile.folderId) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(tile.folderId) {
            tile.cover?.let { bitmap = thumbs.load(it.id) }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                Image(
                    bitmap = bmp,
                    contentDescription = tile.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(tile.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

@Composable
private fun BackToCollectionsChip(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text(LocalStrings.current.backToCollections)
    }
}

/** Icon + label action used by the album selection bar. */
@Composable
private fun RowScope.BarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/**
 * Two-finger pinch that reports a per-event distance ratio, used to change the
 * grid column count. A single finger is deliberately left unconsumed so the
 * list/grid keeps scrolling normally; only once a second pointer is down does
 * this handler consume the moves, taking over from the scroll.
 */
private fun Modifier.pinchToColumnCount(
    onPinchStart: () -> Unit,
    onScale: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pressed = awaitPointerEvent(PointerEventPass.Main).changes.filter { it.pressed }
        while (pressed.size < 2) {
            if (pressed.isEmpty()) return@awaitEachGesture // first finger lifted
            pressed = awaitPointerEvent(PointerEventPass.Main).changes.filter { it.pressed }
        }
        onPinchStart()
        var last = distanceOf(pressed)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val now = event.changes.filter { it.pressed }
            if (now.size < 2) break
            val d = distanceOf(now)
            if (last > 0f) onScale(d / last)
            last = d
            now.forEach { it.consume() }
        }
    }
}

/** Distance between the first two active pointers. */
private fun distanceOf(changes: List<PointerInputChange>): Float {
    val a = changes[0].position
    val b = changes[1].position
    return (a - b).getDistance()
}

/**
 * Paint-to-select on the uniform grid: only armed while a selection is active,
 * it waits past the touch slop so a plain tap still reaches the tile's own
 * click handler, then continuously feeds every cell the finger drags over into
 * [onSelectAt] (which only ever adds, mirroring Google Photos). Single-finger
 * scrolling outside selection mode is untouched because the modifier is inert
 * when [active] is false.
 */
private fun Modifier.dragSweepSelect(
    active: Boolean,
    onSelectAt: (Offset) -> Unit,
): Modifier = pointerInput(active) {
    if (!active) return@pointerInput
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var dragging = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.pressed } ?: break
            if (!dragging) {
                if ((change.position - down.position).getDistance() <= slop) continue
                dragging = true
                onSelectAt(down.position)
            }
            onSelectAt(change.position)
            change.consume()
        }
    }
}

/** Index of the visible grid cell under [pos] (coordinates relative to the grid). */
private fun LazyGridState.itemIndexAt(pos: Offset): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull {
        pos.x >= it.offset.x && pos.x < it.offset.x + it.size.width &&
            pos.y >= it.offset.y && pos.y < it.offset.y + it.size.height
    }?.index
