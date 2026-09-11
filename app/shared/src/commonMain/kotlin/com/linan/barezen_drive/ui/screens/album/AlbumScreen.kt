package com.linan.barezen_drive.ui.screens.album

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.linan.barezen_drive.ui.media.formatMonthLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.i18n.LocalStrings

private const val PAGE_SIZE = 200

private data class AlbumGroup(val label: String, val files: List<FileDto>)

private fun groupByMonth(files: List<FileDto>): List<AlbumGroup> {
    val tz = TimeZone.currentSystemDefault()
    return files
        .groupBy { f ->
            val local = runCatching { Instant.parse(f.updatedAt).toLocalDateTime(tz) }.getOrNull()
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    repo: FilesRepository,
    thumbs: ThumbnailLoader,
    uploader: UploadManager,
    onBack: (() -> Unit)?,
    onPreview: (List<FileDto>, Int, Boolean) -> Unit,
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

    LaunchedEffect(Unit) {
        albumFolderId = AlbumFolder.resolve(repo, device)
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
            repo.album(PAGE_SIZE, cursor, albumFolderId).fold(
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


    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
        topBar = {
            TopAppBar(
                title = { Text(LocalStrings.current.tabAlbum) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LocalStrings.current.actionBack)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { imagePicker() }) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = LocalStrings.current.pickFromGallery)
                    }
                },
            )
        },
    ) { pad ->
        val flat = groups.flatMap { it.files }
        when {
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
                            onClick = {
                                val index = flat.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
                                onPreview(flat, index, true)
                            },
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

        // Upload progress dialog: progress, speed and cancel.
        if (pendingUploads.isNotEmpty()) {
            val p = progress
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                title = { Text(LocalStrings.current.uploadingGeneric) },
                text = {
                    Column {
                        Text(
                            p.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
private fun Centered(pad: androidx.compose.foundation.layout.PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/** Waterfall tile: loads the cover, then occupies its intrinsic aspect ratio. */
@Composable
private fun AlbumTile(file: FileDto, thumbs: ThumbnailLoader, onClick: () -> Unit) {
    var bitmap by remember(file.id) { mutableStateOf<ImageBitmap?>(null) }
    // Decoupled from bitmap: load() returning null means "no cover" (not still
    // loading), so the tile settles on the type-icon placeholder instead of
    // spinning forever like it used to.
    var loaded by remember(file.id) { mutableStateOf(false) }
    LaunchedEffect(file.id) {
        bitmap = thumbs.load(file.id)
        loaded = true
    }
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        val bmp = bitmap
        when {
            bmp != null && bmp.width > 0 && bmp.height > 0 -> Image(
                bitmap = bmp,
                contentDescription = file.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bmp.width.toFloat() / bmp.height),
                contentScale = ContentScale.Crop,
            )
            else -> Box(
                Modifier.fillMaxWidth().height(110.dp).background(
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
    }
}
