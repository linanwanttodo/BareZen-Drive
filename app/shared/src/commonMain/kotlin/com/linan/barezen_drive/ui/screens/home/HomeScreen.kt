package com.linan.barezen_drive.ui.screens.home

import androidx.compose.runtime.collectAsState
import com.linan.barezen_drive.core.dto.ServerStatsDto
import com.linan.barezen_drive.platform.monotonicNowMs
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha
import com.linan.barezen_drive.core.dto.AlbumPage
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.ui.media.FileThumbnail
import com.linan.barezen_drive.ui.media.ThumbnailLoader
import com.linan.barezen_drive.ui.media.formatDateTime
import com.linan.barezen_drive.ui.screens.files.formatFileSize
import com.linan.barezen_drive.ui.screens.preview.PreviewKind
import io.ktor.utils.io.ByteReadChannel
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.i18n.LocalStrings

private const val RECENT_LIMIT = 12
private const val ALBUM_STRIP_SIZE = 12

/**
 * Home tab: an album strip (latest photos, "see all" opens the full
 * monthly timeline), then the most recently changed files. The status
 * dashboard planned for the next phase slots in above the album section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repo: FilesRepository,
    thumbs: ThumbnailLoader,
    onOpenAlbum: () -> Unit,
    onPreview: (List<FileDto>, Int) -> Unit,
    saver: (name: String, mime: String?, open: suspend () -> ByteReadChannel) -> Unit,
    themeToggle: (@Composable () -> Unit)? = null,
) {
    var recent by remember { mutableStateOf<List<FileDto>?>(null) }
    var album by remember { mutableStateOf<List<FileDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var stats by remember { mutableStateOf<ServerStatsDto?>(null) }
    var latency by remember { mutableStateOf<Long?>(null) }

    // Sections load independently so one failure cannot blank the page; the
    // album strip simply stays hidden when its request fails.
    LaunchedEffect(Unit) {
        while (true) {
            stats = repo.serverStats().getOrNull()
            latency = runCatching { repo.ping() }.getOrNull()
            delay(3_000)
        }
    }

    LaunchedEffect(Unit) {
        repo.recentFiles(RECENT_LIMIT).fold(
            onSuccess = { recent = it.files; error = null },
            onFailure = { if (recent == null) error = it.message?.takeIf { m -> m.isNotBlank() } ?: I18n.strings.loadFailed },
        )
        repo.album(ALBUM_STRIP_SIZE).fold(
            onSuccess = { album = it.files },
            onFailure = { /* album strip stays hidden on failure */ },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
        topBar = {
            TopAppBar(
                title = { Text(LocalStrings.current.tabHome) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = { themeToggle?.invoke() },
            )
        },
    ) { pad ->
        val recentList = recent
        val albumList = album
        // Bottom clearance lets the last rows scroll clear of the floating
        // glass bar while content still flows behind it for the refraction.
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 112.dp),
        ) {
            // ---- Server status section ----
            item(key = "server_status") {
                ServerStatusCard(stats, latency)
            }
            // ---- Album section ----
            if (albumList != null && albumList.isNotEmpty()) {
                item(key = "album_header") {
                    SectionHeader(
                        title = LocalStrings.current.tabAlbum,
                        action = LocalStrings.current.seeAll,
                        onAction = onOpenAlbum,
                    )
                }
                item(key = "album_strip") {
                    val images = albumList
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    ) {
                        items(images, key = { it.id }) { file ->
                            Box(Modifier.clickable {
                                val index = images.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
                                onPreview(images, index)
                            }) {
                                FileThumbnail(file, thumbs, edge = 108.dp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            // ---- Recent section ----
            item(key = "recent_header") {
                SectionHeader(title = LocalStrings.current.homeRecent, action = null, onAction = null)
            }
            if (error != null && recentList == null) {
                item(key = "recent_error") {
                    Text(
                        error ?: LocalStrings.current.loadFailed,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else if (recentList == null) {
                item(key = "recent_loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (recentList.isEmpty()) {
                item(key = "recent_empty") {
                    Text(
                        LocalStrings.current.noRecentFiles,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(recentList, key = { it.id }) { file ->
                    RecentRow(
                        file = file,
                        thumbs = thumbs,
                        onOpen = { openRecent(file, recentList, onPreview, saver, repo) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun openRecent(
    file: FileDto,
    listing: List<FileDto>,
    onPreview: (List<FileDto>, Int) -> Unit,
    saver: (name: String, mime: String?, open: suspend () -> ByteReadChannel) -> Unit,
    repo: FilesRepository,
) {
    when (PreviewKind.of(file)) {
        PreviewKind.IMAGE -> {
            val images = listing.filter { PreviewKind.of(it) == PreviewKind.IMAGE }
            val index = images.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
            onPreview(images, index)
        }
        PreviewKind.VIDEO, PreviewKind.AUDIO, PreviewKind.TEXT, PreviewKind.PDF ->
            onPreview(listOf(file), 0)
        PreviewKind.OTHER -> saver(file.name, file.mimeType) { repo.download(file.id) }
    }
}

@Composable
private fun SectionHeader(title: String, action: String?, onAction: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun RecentRow(
    file: FileDto,
    thumbs: ThumbnailLoader,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumbnail(file, thumbs, edge = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatFileSize(file.size)} · ${formatDateTime(file.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatBytesPerSec(v: Long): String = if (v < 0) "—" else formatFileSize(v) + "/s"

/** Top server status panel: CPU / memory / disk / latency / up-down throughput. */
@Composable
private fun ServerStatusCard(stats: ServerStatsDto?, latency: Long?) {
    val panelAlpha = com.linan.barezen_drive.ui.theme.LocalPanelAlpha.current
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = (panelAlpha * 1.4f).coerceIn(0.55f, 0.97f)),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.MonitorHeart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(LocalStrings.current.serverStatus, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    latency?.let { "${it}ms" } ?: "—",
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        latency == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        latency < 100 -> MaterialTheme.colorScheme.primary
                        latency < 400 -> MaterialTheme.typography.labelMedium.color
                        else -> MaterialTheme.colorScheme.error
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            val st = stats
            if (st == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(LocalStrings.current.readingServerStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val cpuFrac = if (st.cpuPercent < 0) 0f else (st.cpuPercent / 100.0).toFloat()
                    val memFrac = if (st.memTotalBytes <= 0) 0f else st.memUsedBytes.toFloat() / st.memTotalBytes
                    val diskUsedFrac = if (st.diskTotalBytes <= 0 || st.diskFreeBytes < 0) 0f
                        else ((st.diskTotalBytes - st.diskFreeBytes).toFloat() / st.diskTotalBytes)

                    MetricChip("CPU", if (st.cpuPercent < 0) "—" else "${st.cpuPercent.toInt()}%", cpuFrac)
                    MetricChip(LocalStrings.current.metricMemory, if (st.memTotalBytes <= 0) "—" else formatFileSize(st.memUsedBytes), memFrac)
                    MetricChip(LocalStrings.current.metricDiskFree, if (st.diskFreeBytes < 0) "—" else formatFileSize(st.diskFreeBytes), diskUsedFrac)
                    MetricChip(LocalStrings.current.actionDownload, formatBytesPerSec(st.netRxBytesPerSec), null)
                    MetricChip(LocalStrings.current.actionUpload, formatBytesPerSec(st.netTxBytesPerSec), null)
                }
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String, usageFraction: Float? = null) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val progress = MaterialTheme.colorScheme.primary
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        if (usageFraction != null) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.size(20.dp)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val stroke = 2.5.dp.toPx()
                    drawArc(
                        color = track,
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                    drawArc(
                        color = progress,
                        startAngle = -90f, sweepAngle = 360f * usageFraction.coerceIn(0f, 1f), useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
            }
        }
    }
}