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
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How often a resident tile re-asks for a cover that is still missing.
 *
 * Only the cross-device case reaches this: a cover generated on another phone
 * never fires this process's readyIds event, so the loader's 60s negative
 * cache has to expire before a retry can even succeed. Five minutes is well
 * past that TTL and slow enough to be invisible on battery.
 */
private val COVER_FALLBACK_INTERVAL = 5.minutes

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
    val kind = PreviewKind.of(file)
    val wantsCover = file.hasThumbnail || kind == PreviewKind.IMAGE || kind == PreviewKind.VIDEO
    LaunchedEffect(file.id) {
        if (!wantsCover) return@LaunchedEffect
        bitmap = loader.load(file.id)
    }
    // The server generates covers lazily, so a tile that asked too early has to
    // be told when one appears.
    //
    // It used to be three 30s retries per visible row instead: restarted on
    // every scroll, and up to 15s late because the loader's negative cache
    // expires at 60s while the third retry fired at exactly 60s.
    //
    // Two triggers, one coroutine, so a resident tile costs one timer and not
    // two. The event is the normal path - immediate, and free while nothing is
    // happening. The slow tick is kept on purpose: it is the only thing that
    // covers a cover generated on *another* device, where this process never
    // sees the insert and so never gets the event. Both stop as soon as the
    // bitmap lands, which is also when the old retry loop gave up.
    LaunchedEffect(file.id, wantsCover) {
        if (!wantsCover) return@LaunchedEffect
        merge<Unit>(
            snapshotFlow { loader.readyIds.value }.filter { file.id in it }.map { },
            flow { while (true) { delay(COVER_FALLBACK_INTERVAL); emit(Unit) } },
        )
            // takeWhile does the stopping: a bare `return` out of a collect
            // lambda is not allowed, and the point is to end the flow once the
            // cover has landed and both triggers have nothing left to say.
            .takeWhile {
                bitmap = loader.load(file.id)
                bitmap == null
            }
            .collect()
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
