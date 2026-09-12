package com.linan.barezen_drive.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size

/**
 * Bottom layer of the app: the user wallpaper when they picked one, otherwise
 * the plain themed background. (The bundled Apple-shot fallback wallpaper was
 * removed on purpose - defaults must ship without third-party imagery.)
 */
@Composable
fun WallpaperLayer(imageBitmap: ImageBitmap?) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (imageBitmap != null) {
            Image(
                painter = BitmapPainter(imageBitmap),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
