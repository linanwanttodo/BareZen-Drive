package com.linan.barezen_drive.ui.screens.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.local.AppPreferences
import com.linan.barezen_drive.data.repo.FilesRepository
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import com.linan.barezen_drive.i18n.LocalStrings

/**
 * Media3/ExoPlayer streaming playback. The player fetches from a short-lived
 * signed URL so no custom auth DataSource is needed; the server's
 * PartialContent support gives seek/progressive playback for free.
 */
@Composable
actual fun PlatformMediaPlayer(file: FileDto, repo: FilesRepository, isAudio: Boolean) {
    val context = LocalContext.current
    var url by remember(file.id) { mutableStateOf<String?>(null) }
    var error by remember(file.id) { mutableStateOf(false) }

    LaunchedEffect(file.id) {
        repo.fileLink(file.id).fold(
            onSuccess = { url = repo.baseUrl + it.url },
            onFailure = { error = true },
        )
    }

    if (error) {
        MediaPlaceholder(LocalStrings.current.playbackUrlUnavailable)
        return
    }

    val player = remember(file.id) {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(url) {
        url?.let {
            player.setMediaItem(MediaItem.fromUri(it))
            player.prepare()
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (url == null) {
            CircularProgressIndicator()
        } else if (isAudio) {
            // Audio keeps the controller bar in a compact strip, not fullscreen.
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun MediaPlaceholder(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * PDF preview built on the platform PdfRenderer: no third-party native
 * libraries (the previous pdfium bundle shipped 4KB-aligned .so files that
 * tripped Android 15+ 16KB-page compatibility checks). Pages render lazily
 * into a swipeable pager; encryption/corruption surfaces as an error state.
 */
@Composable
actual fun PlatformPdfViewer(file: FileDto, repo: FilesRepository) {
    val context = LocalContext.current
    var pdfFile by remember(file.id) { mutableStateOf<java.io.File?>(null) }
    var error by remember(file.id) { mutableStateOf(false) }

    LaunchedEffect(file.id) {
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val target = java.io.File(context.cacheDir, "preview-${file.id}.pdf")
                repo.download(file.id).let { channel ->
                    target.outputStream().use { out ->
                        channel.toInputStream().use { ins ->
                            ins.copyTo(out)
                        }
                    }
                }
                target
            }
        }.fold(
            onSuccess = { pdfFile = it },
            onFailure = { error = true },
        )
    }

    val local = pdfFile
    when {
        error -> MediaPlaceholder(LocalStrings.current.pdfLoadFailed)
        local != null -> PdfPages(local)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(LocalStrings.current.downloadingPdf, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** One shared PdfRenderer for the document; null when it cannot be opened. */
@Composable
private fun rememberPdfRenderer(file: java.io.File): Pair<android.graphics.pdf.PdfRenderer?, Boolean> {
    var renderer by remember(file.path) { mutableStateOf<android.graphics.pdf.PdfRenderer?>(null) }
    var failed by remember(file.path) { mutableStateOf(false) }
    DisposableEffect(file.path) {
        val r = runCatching {
            val pfd = android.os.ParcelFileDescriptor.open(
                file,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            )
            android.graphics.pdf.PdfRenderer(pfd)
        }.getOrNull()
        renderer = r
        failed = r == null
        onDispose {
            runCatching { r?.close() }
        }
    }
    return renderer to failed
}

/** Renders a single page at up to [MAX_PAGE_PX] on the long edge. */
private fun renderPage(renderer: android.graphics.pdf.PdfRenderer, index: Int): android.graphics.Bitmap? {
    var page: android.graphics.pdf.PdfRenderer.Page? = null
    try {
        page = renderer.openPage(index)
        val longEdge = maxOf(page.width, page.height).toFloat()
        val scale = (MAX_PAGE_PX / longEdge).coerceAtMost(2.5f).coerceAtLeast(0.5f)
        val bitmap = android.graphics.Bitmap.createBitmap(
            (page.width * scale).toInt().coerceAtLeast(1),
            (page.height * scale).toInt().coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val matrix = android.graphics.Matrix().apply { setScale(scale, scale) }
        page.render(bitmap, null, matrix, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    } catch (t: Throwable) {
        return null
    } finally {
        runCatching { page?.close() }
    }
}

private enum class PdfLayout { PAGE, CONTINUOUS }

/** Localized PDF layout title. Read inside composition so a language switch recomposes. */
@Composable
private fun pdfLayoutLabel(layout: PdfLayout): String = when (layout) {
    PdfLayout.PAGE -> LocalStrings.current.pageTurn
    PdfLayout.CONTINUOUS -> LocalStrings.current.mediaTallImage
}

/** Holds rendered pages and serializes PdfRenderer access (one open page at a time). */
private class PdfPageCache {
    val mutex = kotlinx.coroutines.sync.Mutex()
    val bitmaps = mutableMapOf<Int, android.graphics.Bitmap?>()

    suspend fun bitmapFor(renderer: android.graphics.pdf.PdfRenderer, index: Int): android.graphics.Bitmap? =
        mutex.withLock {
            bitmaps.getOrPut(index) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { renderPage(renderer, index) }
            }
        }
}

@Composable
private fun PdfPages(file: java.io.File) {
    val (renderer, failed) = rememberPdfRenderer(file)
    val pageCount = renderer?.pageCount ?: 0
    val cache = remember(file.path) { PdfPageCache() }
    var layout by remember(file.path) {
        mutableStateOf(if (AppPreferences.get().pdfViewMode == 1) PdfLayout.CONTINUOUS else PdfLayout.PAGE)
    }

    when {
        renderer == null && failed -> MediaPlaceholder(LocalStrings.current.pdfCannotOpen)
        pageCount == 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> Box(Modifier.fillMaxSize()) {
            when (layout) {
                PdfLayout.PAGE -> PdfPager(renderer!!, pageCount, cache)
                PdfLayout.CONTINUOUS -> PdfContinuous(renderer!!, pageCount, cache)
            }
            // Layout switcher, persisted across sessions.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        MaterialTheme.shapes.small,
                    ),
            ) {
                PdfLayout.entries.forEach { option ->
                    TextButton(
                        onClick = {
                            layout = option
                            AppPreferences.get().pdfViewMode = if (option == PdfLayout.CONTINUOUS) 1 else 0
                        },
                    ) {
                        Text(
                            pdfLayoutLabel(option),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (layout == option) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPager(
    renderer: android.graphics.pdf.PdfRenderer,
    pageCount: Int,
    cache: PdfPageCache,
) {
    val pagerState = androidx.compose.foundation.pager.rememberPagerState { pageCount }
    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().weight(1f),
        ) { index ->
            var bitmap by remember(index) { mutableStateOf(cache.bitmaps[index]) }
            LaunchedEffect(renderer, index) {
                bitmap = cache.bitmapFor(renderer, index)
            }
            PdfPageImage(bitmap, index, Modifier.fillMaxSize())
        }
        Text(
            "${pagerState.currentPage + 1} / $pageCount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .wrapContentHeight(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * Continuous "long image" mode: pages stacked in one vertical list. One
 * finger scrolls normally; a two-finger pinch adjusts a document zoom factor
 * (1x-4x) - zoomed pages grow past the screen width and the list gains
 * horizontal panning. Double-tap resets to 1x.
 */
@Composable
private fun PdfContinuous(
    renderer: android.graphics.pdf.PdfRenderer,
    pageCount: Int,
    cache: PdfPageCache,
) {
    var zoom by remember { mutableStateOf(1f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenWidth = with(density) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() }
    val hScroll = remember { androidx.compose.foundation.ScrollState(0) }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll, enabled = zoom > 1.05f)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var pinching = false
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                if (pressed >= 2) {
                                    pinching = true
                                    zoom = (zoom * event.calculateZoom()).coerceIn(1f, 4f)
                                    event.changes.forEach { it.consume() }
                                } else if (pinching) {
                                    // Swallow leftovers after the pinch ends so the
                                    // scroll container never picks the gesture up.
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (zoom < 1.05f) zoom = 1f
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { zoom = 1f })
                    },
            ) {
                items(pageCount, key = { it }) { index ->
                    var bitmap by remember(index) { mutableStateOf(cache.bitmaps[index]) }
                    LaunchedEffect(renderer, index) {
                        bitmap = cache.bitmapFor(renderer, index)
                    }
                    val bmp = bitmap
                    Box(
                        Modifier
                            .width(screenWidth * zoom)
                            .padding(vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            bmp != null -> Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = LocalStrings.current.pageNumber(index + 1),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(bmp.width.toFloat() / bmp.height),
                                contentScale = ContentScale.FillWidth,
                            )
                            else -> Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.707f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator() }
                        }
                    }
                }
            }
            if (zoom > 1.05f) {
            Text(
                "${"${(zoom * 100).toInt()}"}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PdfPageImage(bitmap: android.graphics.Bitmap?, index: Int, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = LocalStrings.current.pageNumber(index + 1),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            else -> CircularProgressIndicator()
        }
    }
}

private const val MAX_PAGE_PX = 2048
