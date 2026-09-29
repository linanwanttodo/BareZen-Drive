package com.linan.barezen_drive.system

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.readBounded
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentLength
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun avatarKey(userId: UUID) = "avatars/$userId.jpg"
private const val AVATAR_MAX_BYTES = 4L * 1024 * 1024

/**
 * Profile avatars: one small JPEG per user under the storage root. Upload is
 * the owner only, decode + downscale server-side so a 4K selfie never lands
 * in the app as a 8 MB avatar.
 *
 * Reading is public on purpose: the share page has to show a face before anyone
 * has signed in, and an avatar id is not a secret worth protecting. Writing is
 * not - it replaces another account's picture - so it runs inside the
 * authentication block. (It used to sit outside it and call `call.userId`
 * anyway, which is a principal that never exists there: every upload answered
 * 401.)
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

    authenticate("auth-jwt") {
        put("/api/me/avatar") {
            val uid = call.userId
            // Decoding a photo is CPU work measured in hundreds of milliseconds
            // and it allocates a raster: doing it on the event-loop thread would
            // stall every other request on the box for its duration, so the whole
            // handler body - read, decode, scale, store - runs on an IO thread.
            // Reading the body also has to be there: the channel is pumped by
            // the loop.
            withContext(Dispatchers.IO) {
                // Bounded read: receive<ByteArray> would buffer an unbounded
                // body first and check afterwards; this stops at the limit
                // whatever the client sends.
                val raw = readBounded(call.receiveChannel(), AVATAR_MAX_BYTES, call.request.contentLength() ?: 0)
                    ?: throw ApiException.tooLarge("头像过大（最大 4MB）")
                if (raw.isEmpty()) throw ApiException.badRequest("empty avatar")
                val scaled = runCatching { scaleTo512(raw) }.getOrElse {
                    throw ApiException.badRequest("not a readable image")
                }
                storage.put(avatarKey(uid), scaled.toByteReadChannel())
            }
            call.respondText("ok")
        }
    }
}

/**
 * Decoding cap for the OUTPUT: 12M pixels is far past any avatar and keeps a
 * small-file/decompression-bomb from producing gigabytes of heap. It bounds what
 * is drawn, not what is read - the input is subsampled, so a 50-megapixel photo
 * costs about as much as a small one.
 */
private const val AVATAR_MAX_PIXELS = 12_000_000L

/**
 * Decodes any supported image and re-encodes it as a square-cropped JPEG capped
 * at 512px.
 *
 * One pass, with subsampling: ImageIO decodes straight to the size the output
 * needs, so a 6000x4000 upload rasterises at about a megapixel instead of
 * twenty-four. Decoding the full image first and throwing it away was the old
 * behaviour - ~96 MB of short-lived heap per request, which is also why
 * anything above the pixel cap had to be refused outright.
 */
private fun scaleTo512(src: ByteArray): ByteArray {
    // Read header and pixels through the same reader: opening the image twice
    // (once for the dimensions, once for the raster) is a second full parse, and
    // the first stream is the one that used to leak on an unsupported encoding.
    ImageIO.createImageInputStream(ByteArrayInputStream(src))?.use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) throw IllegalArgumentException("unreadable image")
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0) throw IllegalArgumentException("unreadable image")
            val size = minOf(width, height)
            val side = minOf(size, AVATAR_SIDE)
            // Keep at most AVATAR_MAX_PIXELS of source pixels by taking every
            // n-th pixel, which is what the decoder is asked to do. Factor 1
            // (no subsampling) for anything small enough not to need it.
            val subsample = maxOf(1, ceilDiv(size.toLong() * size, AVATAR_MAX_PIXELS).toInt())
            val param = reader.defaultReadParam.apply {
                // (xPeriod, yPeriod, xOffset, yOffset): take every n-th pixel.
                if (subsample > 1) setSourceSubsampling(subsample, subsample, 0, 0)
            }
            val img = reader.read(0, param)
                ?: throw IllegalArgumentException("unreadable image")
            // Square center-crop first, then scale: avatars render as circles.
            val cropped = img.getSubimage(
                (img.width - size) / 2,
                (img.height - size) / 2,
                size,
                size,
            )
            val out = BufferedImage(AVATAR_SIDE, AVATAR_SIDE, BufferedImage.TYPE_INT_RGB)
            val g: Graphics2D = out.createGraphics()
            g.drawImage(cropped, 0, 0, AVATAR_SIDE, AVATAR_SIDE, null)
            g.dispose()
            val bos = ByteArrayOutputStream()
            ImageIO.write(out, "jpg", bos)
            return bos.toByteArray()
        } finally {
            reader.dispose()
        }
    } ?: throw IllegalArgumentException("unreadable image")
}

/** The avatar is stored and served as a 512px square, so the output is fixed. */
private const val AVATAR_SIDE = 512

/** Ceiling division; plain integer maths for non-negative values. */
private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

private fun ByteArray.toByteReadChannel(): io.ktor.utils.io.ByteReadChannel =
    io.ktor.utils.io.ByteReadChannel(this)
