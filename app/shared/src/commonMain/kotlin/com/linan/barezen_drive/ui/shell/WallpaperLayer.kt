package com.linan.barezen_drive.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import barezen_drive.app.shared.generated.resources.Res
import barezen_drive.app.shared.generated.resources.wallpaper_light
import org.jetbrains.compose.resources.painterResource

/**
 * Bottom layer of the app: the user wallpaper when set, otherwise the bundled
 * ambient wallpaper. Always having a rich image here is what makes the
 * liquid-glass bars visible - over a flat color the refraction has nothing to
 * work with and the glass degrades to a plain translucent strip. The image is
 * drawn crisp (no frost): the refraction comes from the glass bars above it.
 */
@Composable
fun WallpaperLayer(imageBitmap: ImageBitmap?) {
    val painter: Painter = if (imageBitmap != null) {
        BitmapPainter(imageBitmap)
    } else {
        painterResource(Res.drawable.wallpaper_light)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}
