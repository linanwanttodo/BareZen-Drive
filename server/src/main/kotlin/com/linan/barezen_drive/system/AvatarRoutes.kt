package com.linan.barezen_drive.system

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
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
private const val AVATAR_MAX_BYTES = 4 * 1024 * 1024

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
        val raw = call.receive<ByteArray>()
        if (raw.isEmpty()) throw ApiException.badRequest("empty avatar")
        if (raw.size > AVATAR_MAX_BYTES) throw ApiException.badRequest("avatar too large (max 4MB)")
        val scaled = runCatching { scaleTo512(raw) }.getOrElse {
            throw ApiException.badRequest("not a readable image")
        }
        storage.put(avatarKey(uid), scaled.toByteReadChannel())
        call.respondText("ok")
    }
}

/** Decodes any supported image and re-encodes it as a square-cropped JPEG capped at 512px. */
private fun scaleTo512(src: ByteArray): ByteArray {
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
