package com.linan.barezen_drive.ui.screens.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.linan.barezen_drive.i18n.LocalStrings

/**
 * Swipeable full-screen image viewer. Every page loads a low-cost thumbnail
 * first (instant visual) and then the full content; pages support pinch
 * zoom + pan and double-tap to toggle 1x/2.5x.
 */
@Composable
fun ImageViewer(files: List<FileDto>, initialIndex: Int, repo: FilesRepository, onPageChange: (Int) -> Unit = {}) {
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, files.size - 1)) { files.size }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            ZoomableImage(files[page], repo)
        }
        androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) {
            onPageChange(pagerState.currentPage)
        }
        Text(
            "${pagerState.currentPage + 1} / ${files.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    MaterialTheme.shapes.small,
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ZoomableImage(file: FileDto, repo: FilesRepository) {
    var bitmap by remember(file.id) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(file.id) { mutableStateOf(false) }
    LaunchedEffect(file.id) {
        val result = withContext(Dispatchers.Default) {
            runCatching { repo.previewBytes(file.id).decodeToImageBitmap() }
        }
        result.fold(
            onSuccess = { bitmap = it },
            onFailure = { error = true },
        )
    }

    var scale by remember(file.id) { mutableFloatStateOf(1f) }
    var offset by remember(file.id) { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            // Gestures, Google-Photos style: at 1x the horizontal drag belongs
            // to the pager (swipe between photos); only a two-finger pinch
            // starts a zoom. Once zoomed in, drags pan the image instead.
            .pointerInput(file.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (event.changes.any { it.isConsumed }) break
                        if (pressed == 0) break
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val multiTouch = pressed >= 2
                        if (multiTouch || scale > 1f) {
                            val newScale = (scale * zoomChange).coerceIn(1f, 6f)
                            scale = newScale
                            if (newScale > 1f) {
                                offset += panChange
                            } else {
                                offset = Offset.Zero
                            }
                            // Two fingers always belong to the zoom gesture;
                            // a single drag only when the image is zoomed in.
                            if (multiTouch || newScale > 1f) {
                                event.changes.forEach { if (it.pressed) it.consume() }
                            }
                        }
                    }
                }
            }
            .pointerInput(file.id) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.2f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp,
                contentDescription = file.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
            error -> Text(
                LocalStrings.current.imageLoadFailed,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            else -> CircularProgressIndicator()
        }
    }
}
