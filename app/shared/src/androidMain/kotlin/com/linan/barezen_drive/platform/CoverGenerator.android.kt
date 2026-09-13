package com.linan.barezen_drive.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Android cover generation. Decodes images downscaled via inSampleSize (a
 * 50MP photo never materializes as a full bitmap) and extracts the first
 * video frame with MediaMetadataRetriever. EXIF orientation is applied to
 * JPEGs so covers match what the user sees. Any failure returns null.
 */
actual suspend fun generateCover(file: PickedFile): ByteArray? = withContext(Dispatchers.IO) {
    if (file !is AndroidPickedFile) return@withContext null
    runCatching {
        when (coverKind(file)) {
            CoverKind.IMAGE -> imageCover(file)
            CoverKind.VIDEO -> videoCover(file)
            CoverKind.NONE -> null
        }
    }.getOrNull()
}

private enum class CoverKind { IMAGE, VIDEO, NONE }

private fun coverKind(file: AndroidPickedFile): CoverKind {
    val mime = file.mimeType
    if (mime != null) {
        return when {
            mime.startsWith("image/") -> CoverKind.IMAGE
            mime.startsWith("video/") -> CoverKind.VIDEO
            else -> CoverKind.NONE
        }
    }
    // SAF providers occasionally report no mime type; fall back to the extension.
    val lower = file.name.lowercase()
    return when {
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") -> CoverKind.IMAGE
        lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm") ||
            lower.endsWith(".3gp") -> CoverKind.VIDEO
        else -> CoverKind.NONE
    }
}

private fun imageCover(file: AndroidPickedFile): ByteArray? {
    val resolver = file.ctx.contentResolver
    // Bounds pass only, to pick inSampleSize without allocating pixels.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val src = resolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: return null
    val upright = applyExifOrientation(file, src)
    return scaledJpeg(upright, MAX_EDGE)
}

private fun applyExifOrientation(file: AndroidPickedFile, src: Bitmap): Bitmap {
    val exif = file.ctx.contentResolver.openInputStream(file.uri)?.use {
        ExifInterface(it)
    } ?: return src
    val degrees = when (
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return src
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}

private fun videoCover(file: AndroidPickedFile): ByteArray? {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.ctx, file.uri)
        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        return scaledJpeg(frame, MAX_EDGE)
    } finally {
        retriever.release()
    }
}

private const val MAX_EDGE = 512

private fun scaledJpeg(src: Bitmap, maxEdge: Int): ByteArray? {
    if (src.width <= 0 || src.height <= 0) return null
    val scale = minOf(1f, maxEdge.toFloat() / maxOf(src.width, src.height))
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        src
    }
    val out = ByteArrayOutputStream()
    if (!scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)) return null
    return out.toByteArray()
}
