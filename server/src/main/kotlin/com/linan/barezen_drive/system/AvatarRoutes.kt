package com.linan.barezen_drive.system

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.readBounded
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.utils.io.toByteArray
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO

private fun avatarKey(userId: UUID) = "avatars/$userId.jpg"
private const val AVATAR_MAX_BYTES = 4L * 1024 * 1024

/**
 * Profile avatars: one small JPEG per user under the storage root. Upload is
 * the owner only, decode + downscale server-side so a 4K selfie never lands
 * in the app as a 8 MB avatar.
 */
fun Route.avatarRoutes(storage: StorageProvider) {
    get("/api/users/{id}/avatar") {
        val id = call.parameters["id"]!!.toUuidOrBadRequest()
        val key = avatarKey(id)
        if (!storage.exists(key)) {
            call.respondText("no avatar", status = HttpStatusCode.NotFound)
        } else {
            call.respondBytes(storage.get(key).toByteArray(), ContentType.Image.JPEG)
        }
    }

    put("/api/me/avatar") {
        val uid = call.userId
        // Bounded read: receive<ByteArray> would buffer an unbounded body first
        // and check afterwards; this stops at the limit whatever the client sends.
        val raw = readBounded(call.receiveChannel(), AVATAR_MAX_BYTES)
            ?: throw ApiException.tooLarge("头像过大（最大 4MB）")
        if (raw.isEmpty()) throw ApiException.badRequest("empty avatar")
        val scaled = runCatching { scaleTo512(raw) }.getOrElse {
            throw ApiException.badRequest("not a readable image")
        }
        storage.put(avatarKey(uid), scaled.toByteReadChannel())
        call.respondText("ok")
    }
}

/** Decoding cap: 12M pixels is far past any avatar source but keeps a
 *  small-file/decompression-bomb PNG from materializing gigabytes of heap. */
private const val AVATAR_MAX_PIXELS = 12_000_000L

/** Decodes any supported image and re-encodes it as a square-cropped JPEG capped at 512px. */
private fun scaleTo512(src: ByteArray): ByteArray {
    // Check header dimensions before any pixel decode (ImageIO.read would
    // allocate the full raster first).
    ImageIO.createImageInputStream(ByteArrayInputStream(src))?.use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) throw IllegalArgumentException("unreadable image")
        val reader = readers.next()
        reader.input = input
        try {
            val w = reader.getWidth(0).toLong()
            val h = reader.getHeight(0).toLong()
            if (w <= 0 || h <= 0 || w * h > AVATAR_MAX_PIXELS) throw IllegalArgumentException("image too large")
        } finally {
            reader.dispose()
        }
    } ?: throw IllegalArgumentException("unreadable image")
    val img = ImageIO.read(ByteArrayInputStream(src))
        ?: throw IllegalArgumentException("unreadable image")
    val size = minOf(img.width, img.height)
    val side = minOf(size, 512)
    // Square center-crop first, then scale: avatars render as circles.
    val cropped = img.getSubimage(
        (img.width - size) / 2,
        (img.height - size) / 2,
        size,
        size,
    )
    val out = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
    val g: Graphics2D = out.createGraphics()
    g.drawImage(cropped, 0, 0, side, side, null)
    g.dispose()
    val bos = ByteArrayOutputStream()
    ImageIO.write(out, "jpg", bos)
    return bos.toByteArray()
}

private fun ByteArray.toByteReadChannel(): io.ktor.utils.io.ByteReadChannel =
    io.ktor.utils.io.ByteReadChannel(this)
