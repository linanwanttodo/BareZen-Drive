package com.linan.barezen_drive.ui.wallpaper

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.files.FileReader
import kotlin.coroutines.resume
import kotlin.js.toJsNumber

private const val MAX_DIM = 384
private const val STORE_KEY_DATA = "bz_wallpaper_data"
private const val STORE_KEY_AVG = "bz_wallpaper_avg"

/**
 * Wallpaper held as a JPEG data URL so it fits inside the localStorage
 * budget: the bitmap is downscaled to at most MAX_DIM on the longer edge
 * before the data URL is written. The average color is computed once on the
 * import canvas and persisted next to the data URL, so restarts do not need
 * to re-extract pixels (pixels is a 1x1 sample carrying that color).
 */
internal class WasmWallpaperImage(
    override val source: String,
    override val pixels: IntArray,
) : WallpaperImage {
    override val width: Int = 1
    override val height: Int = 1

    // The data URL decodes straight into a Compose ImageBitmap via skiko.
    override suspend fun bitmap(): ImageBitmap? = dataUrlToBitmap(source)
}

internal fun dataUrlToBitmap(dataUrl: String): ImageBitmap? = runCatching {
    val b64 = dataUrl.substringAfter("base64,", "")
    if (b64.isEmpty()) return null
    Image.makeFromEncoded(Base64.decode(b64)).toComposeImageBitmap()
}.getOrNull()

private suspend fun FileReader.readArrayBufferSuspending(blob: org.w3c.files.Blob): ArrayBuffer =
    suspendCancellableCoroutine { cont ->
        onload = { cont.resume(result!!.unsafeCast()) }
        onerror = { cont.resume(ArrayBuffer(0)) }
        readAsArrayBuffer(blob)
    }

// Wait for the image element to finish loading. Errors resolve too; the
// caller validates naturalWidth afterwards. The onerror handler matches the
// library's five-parameter ErrorEvent-compatible signature.
private suspend fun HTMLImageElement.awaitLoad() = suspendCancellableCoroutine { cont ->
    onload = { cont.resume(Unit) }
    onerror = { _, _, _, _, _ ->
        val nullJs: JsAny? = null
        cont.resume(Unit)
        nullJs
    }
}

private suspend fun importWallpaper(dataUrl: String): WasmWallpaperImage? =
    withContext(Dispatchers.Default) {
        runCatching {
            val img = document.createElement("img").unsafeCast<HTMLImageElement>()
            img.src = dataUrl
            img.awaitLoad()
            if (img.naturalWidth == 0 || img.naturalHeight == 0) return@runCatching null
            val scale = minOf(1.0, MAX_DIM.toDouble() / maxOf(img.naturalWidth, img.naturalHeight))
            val w = (img.naturalWidth * scale).toInt().coerceAtLeast(1)
            val h = (img.naturalHeight * scale).toInt().coerceAtLeast(1)
            val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
            canvas.width = w
            canvas.height = h
            // getContext returns the plain RenderingContext marker interface,
            // so a normal (unchecked) cast selects the 2D context type.
            val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
            ctx.drawImage(img, 0.0, 0.0, w.toDouble(), h.toDouble())
            val imageData = ctx.getImageData(0.0, 0.0, w.toDouble(), h.toDouble())
            val raw = imageData.data
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0L
            var i = 0
            while (i < w * h) {
                val o = i * 4
                r += raw[o].toInt() and 0xFF
                g += raw[o + 1].toInt() and 0xFF
                b += raw[o + 2].toInt() and 0xFF
                n++
                i++
            }
            val avg = if (n == 0L) {
                0xFF607D8B.toInt()
            } else {
                0xFF000000.toInt() or
                    ((r / n).toInt() shl 16) or
                    ((g / n).toInt() shl 8) or
                    (b / n).toInt()
            }
            // Persist a compressed JPEG data URL (much smaller than PNG) plus
            // the precomputed average color.
            val jpegUrl = canvas.toDataURL("image/jpeg", 0.8.toJsNumber())
            WasmWallpaperImage(jpegUrl, intArrayOf(avg))
        }.getOrNull()
    }

@Composable
actual fun rememberWallpaperPicker(onResult: (WallpaperImage?) -> Unit): () -> Unit {
    return {
        val input = document.createElement("input").unsafeCast<HTMLInputElement>()
        input.type = "file"
        input.accept = "image/*"
        input.onchange = {
            val file = input.files?.item(0)
            if (file == null) {
                onResult(null)
            } else {
                CoroutineScope(Dispatchers.Default).launch {
                    val arr = FileReader().readArrayBufferSuspending(file)
                    val i8 = Int8Array(arr)
                    val bytes = ByteArray(i8.length) { i8[it] }
                    // Base64 the original file directly into an image data
                    // URL; the canvas rescale below bounds the persisted size.
                    val dataUrl = "data:image/png;base64," + Base64.encode(bytes)
                    val img = importWallpaper(dataUrl)
                    if (img != null) {
                        try {
                            window.localStorage.setItem(STORE_KEY_DATA, img.source)
                            window.localStorage.setItem(STORE_KEY_AVG, img.pixels[0].toString())
                        } catch (_: Throwable) {
                            // Quota exceeded or storage blocked: report the
                            // failure through the null result.
                            onResult(null)
                            return@launch
                        }
                    }
                    onResult(img)
                }
            }
        }
        input.click()
    }
}

actual fun loadPersistedWallpaper(): WallpaperImage? {
    val dataUrl = try {
        window.localStorage.getItem(STORE_KEY_DATA)
    } catch (_: Throwable) {
        null
    } ?: return null
    if (dataUrl.isEmpty()) return null
    val avg = try {
        window.localStorage.getItem(STORE_KEY_AVG)?.toIntOrNull()
    } catch (_: Throwable) {
        null
    } ?: 0xFF607D8B.toInt()
    // pixels carries only the persisted average color; the bitmap decodes
    // from the data URL lazily through bitmap().
    return WasmWallpaperImage(dataUrl, intArrayOf(avg))
}

actual fun isBackdropBlurSupported(): Boolean = false
