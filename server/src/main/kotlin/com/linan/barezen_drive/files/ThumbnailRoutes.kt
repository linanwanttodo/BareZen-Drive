package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.readBounded
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.ErrorCodes
import com.linan.barezen_drive.storage.StorageProvider
import com.linan.barezen_drive.storage.thumbKey
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.logging.Logger

private val log = Logger.getLogger("ThumbnailRoutes")

/** Covers are generated client-side; the server only enforces the size ceiling. */
fun Route.thumbnailRoutes(storage: StorageProvider) {
    put("/api/files/{id}/thumbnail") {
        val id = call.parameters["id"]!!.toUuidOrBadRequest()
        val meta = withContext(Dispatchers.IO) { FileService.getFileMeta(call.userId, id) }
        // Bounded read: stop at the cap instead of buffering whatever the client
        // streams, so a missing/stripped Content-Length cannot exhaust heap. The
        // cap is the generator's own constant (ThumbnailService.MAX_THUMB_BYTES)
        // - one number, so what the server publishes and what it accepts agree.
        val bytes = readBounded(call.receiveChannel(), ThumbnailService.MAX_THUMB_BYTES.toLong())
            ?: throw ApiException.badRequest("缩略图超过 512KB", ErrorCodes.FILE_TOO_LARGE)
        // The key is the *source* content hash, so this slot holds "the best
        // cover we have for these bytes", not an immutable object: a client
        // redoing a bad frame, or a better generator, has to be able to replace
        // it. A cover key is a mutable slot in the storage layer (isMutableKey),
        // which is what makes a second PUT land - the plain put used to skip an
        // existing key and the first cover was stored forever. Repeating the
        // same cover still converges on the same bytes, and the write is staged
        // then renamed, so a concurrent GET never sees half a JPEG.
        storage.put(thumbKey(meta.sha256), ByteReadChannel(bytes))
        // Same content => same cover: flag every row that references this sha256.
        withContext(Dispatchers.IO) { ThumbnailService.flagThumbnails(meta) }
        call.respond(HttpStatusCode.OK)
    }

    get("/api/files/{id}/thumbnail") {
        val id = call.parameters["id"]!!.toUuidOrBadRequest()
        val meta = if (call.signedFor(id)) {
            withContext(Dispatchers.IO) { FileService.getFileMetaUnscoped(id) }
        } else {
            withContext(Dispatchers.IO) { FileService.getFileMeta(call.userId, id) }
        }
        // Client-generated covers are the normal path; generate server-side once
        // when the row has no cover yet (old uploads, failed cover PUT). Best
        // effort: a file that cannot be decoded keeps answering 404 below.
        var hasThumb = meta.hasThumbnail
        if (!hasThumb) {
            hasThumb = runCatching { ThumbnailService.ensureThumbnail(storage, meta) }
                .onFailure { log.warning("服务端缩略图生成失败: ${it.message}") }
                .getOrDefault(false)
        }
        if (!hasThumb) throw ApiException.notFound("文件没有缩略图")
        // Covers are bounded (<=512KB) by PUT validation, so buffering for exact
        // Content-Length is safe. exists() first: the hasThumbnail flag can
        // outlive the blob (e.g. a manual store prune), and opening a missing
        // file must answer 404, not 500.
        val key = thumbKey(meta.sha256)
        val bytes = withContext(Dispatchers.IO) {
            if (!storage.exists(key)) throw ApiException.notFound("缩略图不存在")
            storage.get(key).toInputStream().readBytes()
        }
        // The ETag identifies the cover BYTES, not the source file. The slot
        // became replaceable (a client can now overwrite a bad cover, and every
        // row sharing this sha256 sees the new one), so an ETag derived from the
        // source sha would be identical before and after a replacement - and a
        // client that had cached the bad cover would keep serving it forever.
        val etag = coverEtag(bytes)
        call.response.header(HttpHeaders.ETag, etag)
        // Revalidate rather than cache-forever: a 304 costs a round trip and no
        // body, which is the whole point of the ETag, while "immutable" would
        // have pinned whatever the first fetch happened to get.
        call.response.header(HttpHeaders.CacheControl, "public, max-age=0, must-revalidate")
        if (call.request.headers[HttpHeaders.IfNoneMatch]?.contains(etag) == true) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        call.respondBytes(bytes, ContentType.Image.JPEG)
    }
}

/**
 * Strong ETag over the stored cover bytes.
 *
 * Cheap because covers are capped at 512KB by PUT validation and already
 * buffered for the response. SHA-256 rather than a cheaper hash so the tag
 * cannot be guessed to forge a match, and quoted per RFC 9110 - an unquoted
 * entity tag is weak and would not survive a byte-for-byte comparison.
 */
private fun coverEtag(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "\"" + hex + "\""
}
