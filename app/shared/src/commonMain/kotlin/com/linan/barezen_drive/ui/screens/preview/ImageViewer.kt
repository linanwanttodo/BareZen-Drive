package com.linan.barezen_drive.ui.screens.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
fun ImageViewer(files: List<FileDto>, initialIndex: Int, repo: FilesRepository) {
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, files.size - 1)) { files.size }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            ZoomableImage(files[page], repo)
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
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
    }

    Box(
        Modifier
            .fillMaxSize()
            .transformable(transformState)
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
