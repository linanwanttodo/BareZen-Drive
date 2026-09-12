package com.linan.barezen_drive.platform

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.browser.document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import kotlin.coroutines.resume
import kotlin.js.toJsNumber
import kotlin.math.max
import kotlin.math.min

/**
 * Web cover generation: images are decoded through an offscreen <img> element
 * (browsers apply EXIF orientation automatically) and videos are seeked to
 * 0.1s offscreen and drawn onto a canvas. Everything stays inside the browser
 * - the server never decodes media. Failures return null.
 */
actual suspend fun generateCover(file: PickedFile): ByteArray? = withContext(Dispatchers.Default) {
    if (file !is WasmPickedFile) return@withContext null
    runCatching {
        when (coverKindWasm(file)) {
            CoverKindWasm.IMAGE -> imageCoverWasm(file.file)
            CoverKindWasm.VIDEO -> videoCoverWasm(file.file)
            CoverKindWasm.NONE -> null
        }
    }.getOrNull()
}

private enum class CoverKindWasm { IMAGE, VIDEO, NONE }

private fun coverKindWasm(file: WasmPickedFile): CoverKindWasm {
    val mime = file.mimeType
    if (mime != null) {
        return when {
            mime.startsWith("image/") -> CoverKindWasm.IMAGE
            mime.startsWith("video/") -> CoverKindWasm.VIDEO
            else -> CoverKindWasm.NONE
        }
    }
    val lower = file.name.lowercase()
    return when {
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") -> CoverKindWasm.IMAGE
        lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm") -> CoverKindWasm.VIDEO
        else -> CoverKindWasm.NONE
    }
}

private const val MAX_EDGE = 512

private suspend fun imageCoverWasm(file: Blob): ByteArray? {
    val objectUrl = URL.createObjectURL(file)
    try {
        val img = document.createElement("img").unsafeCast<HTMLImageElement>()
        img.awaitLoad(objectUrl)
        if (img.naturalWidth <= 0 || img.naturalHeight <= 0) return null
        val scale = min(1.0, MAX_EDGE.toDouble() / max(img.naturalWidth, img.naturalHeight))
        val w = (img.naturalWidth * scale).toInt().coerceAtLeast(1)
        val h = (img.naturalHeight * scale).toInt().coerceAtLeast(1)
        val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
        canvas.width = w
        canvas.height = h
        // getContext returns the plain RenderingContext marker interface,
        // so a normal (unchecked) cast selects the 2D context type.
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.drawImage(img, 0.0, 0.0, w.toDouble(), h.toDouble())
        return canvas.jpegBytes()
    } finally {
        URL.revokeObjectURL(objectUrl)
    }
}

private suspend fun videoCoverWasm(file: Blob): ByteArray? {
    val objectUrl = URL.createObjectURL(file)
    try {
        val video = document.createElement("video").unsafeCast<HTMLVideoElement>()
        video.muted = true
        video.preload = "auto"
        video.awaitFirstFrame(objectUrl)
        if (video.videoWidth <= 0 || video.videoHeight <= 0) return null
        val scale = min(1.0, MAX_EDGE.toDouble() / max(video.videoWidth, video.videoHeight))
        val w = (video.videoWidth * scale).toInt().coerceAtLeast(1)
        val h = (video.videoHeight * scale).toInt().coerceAtLeast(1)
        val canvas = document.createElement("canvas").unsafeCast<HTMLCanvasElement>()
        canvas.width = w
        canvas.height = h
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.drawImage(video, 0.0, 0.0, w.toDouble(), h.toDouble())
        return canvas.jpegBytes()
    } finally {
        URL.revokeObjectURL(objectUrl)
    }
}

// Errors resolve instead of throwing: the caller validates the decoded
// dimensions, so a broken file just yields "no cover". The onerror handler
// matches the library's five-parameter ErrorEvent-compatible signature.
private suspend fun HTMLImageElement.awaitLoad(src: String) = suspendCancellableCoroutine { cont ->
    onload = { cont.resume(Unit) }
    onerror = { _, _, _, _, _ ->
        val nullJs: JsAny? = null
        cont.resume(Unit)
        nullJs
    }
    this.src = src
}

private suspend fun HTMLVideoElement.awaitFirstFrame(src: String) =
    suspendCancellableCoroutine { cont ->
        var resumed = false
        fun resumeOnce() {
            if (!resumed) {
                resumed = true
                cont.resume(Unit)
            }
        }
        onerror = { _, _, _, _, _ ->
            val nullJs: JsAny? = null
            cont.resume(Unit)
            nullJs
        }
        // Seek to a small offset for a presentable frame (frame 0 is often
        // black); onseeked fires once that frame is ready to be drawn.
        onloadedmetadata = {
            currentTime = 0.1
            onseeked = { resumeOnce() }
        }
        this.src = src
        load()
    }

private fun HTMLCanvasElement.jpegBytes(): ByteArray? {
    val dataUrl = toDataURL("image/jpeg", 0.8.toJsNumber())
    val b64 = dataUrl.substringAfter("base64,", "")
    if (b64.isEmpty()) return null
    return Base64.decode(b64)
}
