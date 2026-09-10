package com.linan.barezen_drive.ui.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.linan.barezen_drive.AndroidContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wallpaper that was copied out of a SAF stream, downscaled and stored in the
 * app-private filesDir so it survives restarts without extra permissions.
 */
internal class AndroidWallpaperImage(private val filePath: String, bitmap: Bitmap) : WallpaperImage {
    override val width: Int = bitmap.width
    override val height: Int = bitmap.height
    override val pixels: IntArray =
        IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
    override val source: String = filePath

    // Decode lazily so recompositions do not re-read the file; the bitmap is
    // small (at most 512 px on the long edge) so memory stays bounded.
    private val cached: Bitmap? by lazy {
        runCatching { BitmapFactory.decodeFile(filePath) }.getOrNull()
    }

    override suspend fun bitmap(): ImageBitmap? = cached?.asImageBitmap()
}

private const val MAX_DIM = 512
private const val WALLPAPER_FILE = "wallpaper.png"

// Reads a SAF uri fully off the main thread, downscales it to at most
// MAX_DIM on the longer edge and writes the result to filesDir. The file
// is rewritten in place so there is exactly one live wallpaper per install.
private suspend fun importWallpaper(ctx: Context, uri: Uri): AndroidWallpaperImage? =
    withContext(Dispatchers.IO) {
        val raw = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null
        val bitmap = runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size) }
            .getOrNull() ?: return@withContext null
        val scale = minOf(1f, MAX_DIM.toFloat() / maxOf(bitmap.width, bitmap.height))
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val out = File(ctx.filesDir, WALLPAPER_FILE)
        runCatching {
            out.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }.getOrNull() ?: return@withContext null
        AndroidWallpaperImage(out.absolutePath, small)
    }

@Composable
actual fun rememberWallpaperPicker(onResult: (WallpaperImage?) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            onResult(null)
        } else {
            // Import runs on a worker thread; the callback fires on the main
            // thread with the persisted image (or null on any failure).
            CoroutineScope(Dispatchers.IO).launch {
                val img = runCatching { importWallpaper(ctx, uri) }.getOrNull()
                withContext(Dispatchers.Main) { onResult(img) }
            }
        }
    }
    return { launcher.launch(arrayOf("image/*")) }
}

/** Reads the wallpaper persisted by a previous pick; null when never set. */
actual fun loadPersistedWallpaper(): WallpaperImage? {
    val ctx: Context = AndroidContext.app
    val file = File(ctx.filesDir, WALLPAPER_FILE)
    if (!file.exists()) return null
    return runCatching {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        AndroidWallpaperImage(file.absolutePath, bmp)
    }.getOrNull()
}

actual fun isBackdropBlurSupported(): Boolean = true
