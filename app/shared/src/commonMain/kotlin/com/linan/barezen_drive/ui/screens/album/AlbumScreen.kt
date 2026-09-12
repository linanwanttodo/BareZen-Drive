package com.linan.barezen_drive.ui.screens.album

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.SwapVert
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.data.upload.UploadManager
import com.linan.barezen_drive.platform.PickedFile
import com.linan.barezen_drive.platform.rememberImagePicker
import com.linan.barezen_drive.platform.monotonicNowMs
import com.linan.barezen_drive.platform.deviceName
import com.linan.barezen_drive.data.repo.AlbumFolder
import com.linan.barezen_drive.ui.media.ThumbnailLoader
import com.linan.barezen_drive.ui.media.fileIcon
import com.linan.barezen_drive.ui.media.formatDateTime
import com.linan.barezen_drive.ui.media.formatMonthLabel
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.platform.copyToClipboard
import com.linan.barezen_drive.platform.rememberFileSaver
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.style.TextOverflow

private const val PAGE_SIZE = 200

private data class AlbumGroup(val label: String, val files: List<FileDto>)

private data class CollectionTile(val name: String, val folderId: String, val cover: FileDto?)

/** Album display time: capture time when the device provided it, else upload time. */
internal fun albumTime(file: FileDto): String = file.takenAt ?: file.updatedAt

private fun groupByMonth(files: List<FileDto>): List<AlbumGroup> {
    val tz = TimeZone.currentSystemDefault()
    return files
        .groupBy { f ->
            val local = runCatching { Instant.parse(albumTime(f)).toLocalDateTime(tz) }.getOrNull()
            local?.year to local?.monthNumber
        }
        .map { (key, fs) -> AlbumGroup(formatMonthLabel(key.first ?: 0, key.second ?: 0), fs) }
}

/**
 * Photo timeline: all images of the account, newest first, bucketed into
 * month sections (for example "September 2026" in the active language) in the
 * device timezone. The waterfall grid
 * keeps original aspect ratios; tapping opens the swipeable preview across
 * every loaded photo. Pages load on demand as the bottom becomes visible.
 */
@Composable
fun AlbumScreen(
    repo: FilesRepository,
    thumbs: ThumbnailLoader,
    uploader: UploadManager,
    onBack: (() -> Unit)?,
    onPreview: (List<FileDto>, Int, Boolean) -> Unit,
    onOpenTransfers: () -> Unit = {},
    avatar: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<AlbumGroup>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var exhausted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingUploads by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var uploadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val progress by uploader.progress.collectAsState()
    // The dedicated album folder tree plus per-device subfolder: the timeline
    // scans only this subtree and uploads land inside it, keeping photos out of
    // the general file tree.
    var albumFolderId by remember { mutableStateOf<String?>(null) }
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
    // Google-Photos selection: long-press a tile to enter, tap toggles, a
    // bottom bar carries the actions until the selection is cleared.
    val selected = remember { mutableStateMapOf<String, FileDto>() }
    val selectionMode = selected.isNotEmpty()
    var collections by remember { mutableStateOf<List<CollectionTile>>(emptyList()) }
    var collectionsLoading by remember { mutableStateOf(false) }
    val allPhotosLabel = LocalStrings.current.allPhotos

    LaunchedEffect(Unit) {
        albumFolderId = AlbumFolder.resolve(repo, device)
        deviceOptions = AlbumFolder.listDevices(repo)
        albumFolderId?.let { categories = repo.contents(it).getOrNull()?.folders?.map { f -> f.name to f.id } ?: emptyList() }
    }

    // Covers for the collections landing: the newest photo of each folder.
    LaunchedEffect(albumFolderId, categories) {
        val scope = albumFolderId ?: return@LaunchedEffect
        if (timelineMode) return@LaunchedEffect
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
        groups = emptyList(); cursor = null; exhausted = false; error = null
        activeCategory = null
        timelineMode = false
        scope.launch {
            categories = id?.let { repo.contents(it).getOrNull()?.folders?.map { f -> f.name to f.id } } ?: emptyList()
        }
        scopeName = name
        // The LaunchedEffect(albumFolderId) reload picks this up.
        albumFolderId = id
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
        scope.launch {
            loading = true
            repo.album(PAGE_SIZE, cursor, activeCategory ?: albumFolderId).fold(
                onSuccess = { page ->
                    val all = groups.flatMap { it.files } + page.files
                    groups = groupByMonth(all)
                    cursor = page.nextCursor
                    if (page.nextCursor == null) exhausted = true
                    error = null
                    loading = false
                },
                onFailure = {
                    loading = false
                    error = it.message?.takeIf { m -> m.isNotBlank() } ?: I18n.strings.loadFailed
                },
            )
        }
    }

    LaunchedEffect(albumFolderId) { if (albumFolderId != null) loadMore() }

    // Upload the picked photos sequentially into the album/<device> folder;
    // cancelling the current one aborts its session and moves to the next.
    LaunchedEffect(pendingUploads) {
        if (pendingUploads.isEmpty()) return@LaunchedEffect
        val target = albumFolderId ?: AlbumFolder.resolve(repo, device)
        albumFolderId = target
        val picks = pendingUploads
        picks.forEachIndexed { i, picked ->
            // Photos carry their phone-album name; the matching category
            // folder is created on first upload (null = straight into the
            // device folder, e.g. web/desktop uploads).
            val perFile = picked.originAlbum?.takeIf { it.isNotBlank() }
                ?.let { cat -> target?.let { t -> AlbumFolder.resolveCategory(repo, t, cat) } }
                ?: target
            uploadJob = launch { uploader.upload(picked, perFile) }
            uploadJob?.join()
        }
        pendingUploads = emptyList()
        // Refresh the grid so new photos appear immediately.
        groups = emptyList()
        cursor = null
        exhausted = false
        loadMore()
    }


    val saver = rememberFileSaver { }
    var shareUrl by remember { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
        bottomBar = {
            if (selectionMode) {
                // Google-Photos action bar: share / download / delete for the
                // whole selection; the tiles themselves carry the checkmarks.
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        BarAction(Icons.Default.Share, LocalStrings.current.actionShare) {
                            scope.launch {
                                selected.values.firstOrNull()?.let { first ->
                                    repo.createShare(fileId = first.id).onSuccess { shareUrl = it.url }
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
                                selected.values.forEach { file -> repo.deleteFile(file.id) }
                                val count = selected.size
                                selected.clear()
                                groups = emptyList(); cursor = null; exhausted = false
                                loadMore()
                            }
                        }
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
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
                    avatar()
                    if (selectionMode) {
                        // Selection mode keeps the bar clean; actions live in
                        // the bottom bar.
                    } else if (timelineMode) {
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
                    // Double arrow: manual upload sits beside the auto-sync /
                    // transfer centre entry, mirroring a netdisk app.
                    IconButton(onClick = onOpenTransfers) {
                        Icon(Icons.Default.SwapVert, contentDescription = LocalStrings.current.transfers)
                    }
                },
            )
        },
    ) { pad ->
        val flat = groups.flatMap { it.files }
        when {
            // Collections landing: one rounded cover per phone album (the
            // first photo), plus an all-photos tile - Google Photos style.
            !timelineMode && scopeName != LocalStrings.current.allDevices -> Centered(pad) {
                if (collectionsLoading) {
                    CircularProgressIndicator()
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        items(collections.size, key = { collections[it].folderId }) { i ->
                            val tile = collections[i]
                            CollectionCoverTile(tile = tile, thumbs = thumbs, onClick = {
                                timelineMode = true
                                if (tile.folderId != albumFolderId) {
                                    activeCategory = tile.folderId
                                    groups = emptyList(); cursor = null; exhausted = false
                                    loadMore()
                                }
                            })
                        }
                    }
                }
            }
            // Dated layout: day sections of uniform squares.
            viewMode == 2 -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item(key = "back") { BackToCollectionsChip { timelineMode = false } }
                val dayGroups = flat.groupBy { formatDateTime(albumTime(it)).take(10) }
                dayGroups.forEach { (day, files) ->
                    item(key = "d_$day") {
                        Text(
                            day,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(files.chunked(3).size) { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                            val rowFiles = files.chunked(3)[row]
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
                            repeat(3 - rowFiles.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                item(key = "loading") {
                    if (loading) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            // Uniform grid: fixed square cells.
            viewMode == 1 -> Column(Modifier.fillMaxSize().padding(pad)) {
                BackToCollectionsChip { timelineMode = false }
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
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
                }
            }
            error != null && groups.isEmpty() -> Centered(pad) {
                Text(error ?: LocalStrings.current.loadFailed, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { error = null; loadMore() }) { Text(LocalStrings.current.actionRetry) }
            }
            groups.isEmpty() && loading -> Centered(pad) { CircularProgressIndicator() }
            groups.isEmpty() -> Centered(pad) {
                Text(LocalStrings.current.noPhotos, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = 110.dp),
                modifier = Modifier.fillMaxSize().padding(pad),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalItemSpacing = 4.dp,
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                groups.forEach { group ->
                    item(key = "h_${group.label}", span = StaggeredGridItemSpan.FullLine) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(group.label, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${group.files.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                    items(group.files, key = { it.id }) { file ->
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
                        Box(
                            Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Fetch the next page whenever this cell becomes visible.
                            LaunchedEffect(groups) { loadMore() }
                            if (error == null) CircularProgressIndicator()
                        }
                    }
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

        // Upload progress dialog: progress, speed and cancel.
        if (pendingUploads.isNotEmpty()) {
            val p = progress
            AlertDialog(
                onDismissRequest = {},
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
                confirmButton = {
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
    var loaded by remember(file.id) { mutableStateOf(false) }
    LaunchedEffect(file.id) {
        bitmap = thumbs.load(file.id)
        loaded = true
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
                if (loaded) {
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
