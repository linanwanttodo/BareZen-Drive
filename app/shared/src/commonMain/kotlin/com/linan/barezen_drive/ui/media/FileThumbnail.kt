package com.linan.barezen_drive.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.ui.screens.preview.PreviewKind
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Cover image for a file row/tile: shows the server-stored client-generated
 * thumbnail when one exists (and falls back to the type icon while loading,
 * when there is none, or when loading fails), so tiles never look broken.
 *
 * Media rows attempt a load even when the row flag says no cover yet: the
 * server generates one lazily on first GET, so the request both returns the
 * fresh cover and makes every later tile load instant. Non-media rows skip
 * the fetch entirely - there is nothing to generate for them.
 */
@Composable
fun FileThumbnail(
    file: FileDto,
    loader: ThumbnailLoader,
    modifier: Modifier = Modifier,
    edge: Dp = 40.dp,
) {
    var bitmap by remember(file.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.id) {
        val kind = PreviewKind.of(file)
        if (file.hasThumbnail || kind == PreviewKind.IMAGE || kind == PreviewKind.VIDEO) {
            // The loader negatively caches a miss for 60s, and this effect
            // would otherwise never rerun on a resident screen: a cover the
            // server generates lazily would stay invisible until the row was
            // scrolled off and back. A few spaced retries cover that window
            // without polling forever.
            repeat(3) { attempt ->
                bitmap = loader.load(file.id)
                if (bitmap != null) return@LaunchedEffect
                if (attempt < 2) delay(30.seconds)
            }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = file.name,
            modifier = modifier.size(edge),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .size(edge)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.shapes.small,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                fileIcon(file.mimeType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
