package com.linan.barezen_drive.ui.wallpaper

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * A wallpaper image the user picked, already downscaled so it fits in the
 * persistence budget of each platform (filesDir on Android, localStorage
 * data URL on wasmJs).
 */
interface WallpaperImage {
    /** Platform-specific handle: absolute file path on Android, data URL on web. */
    val source: String

    /** Downscaled pixels as ARGB ints, row-major, for the average color pass. */
    val pixels: IntArray
    val width: Int
    val height: Int

    /** Decodes the persisted image for drawing; cheap after first call. */
    suspend fun bitmap(): ImageBitmap?
}

/**
 * Opens the platform image picker; on success the picked image is downscaled,
 * persisted and the resulting [WallpaperImage] is handed to [onResult].
 * Returns a launcher callback (null result means the user cancelled).
 */
@Composable
expect fun rememberWallpaperPicker(onResult: (WallpaperImage?) -> Unit): () -> Unit

/** Reads the wallpaper persisted by a previous pick; null when never set. */
expect fun loadPersistedWallpaper(): WallpaperImage?

/** True when this platform can render the real-time backdrop blur effects. */
expect fun isBackdropBlurSupported(): Boolean
