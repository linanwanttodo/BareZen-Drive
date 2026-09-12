package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
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
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.logging.Logger

private val log = Logger.getLogger("ThumbnailRoutes")

/** Covers are generated client-side; the server only enforces the size ceiling. */
private const val MAX_THUMB_BYTES = 512 * 1024

fun Route.thumbnailRoutes(storage: StorageProvider) {
    put("/api/files/{id}/thumbnail") {
        val id = call.parameters["id"]!!.toUuidOrBadRequest()
        val meta = withContext(Dispatchers.IO) { FileService.getFileMeta(call.userId, id) }
        // Reject before buffering when the declared size is already over the cap;
        // the post-receive check covers proxies that strip Content-Length.
        val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (declared != null && declared > MAX_THUMB_BYTES) throw ApiException.badRequest("缩略图超过 512KB")
        val bytes = call.receive<ByteArray>()
        if (bytes.size > MAX_THUMB_BYTES) throw ApiException.badRequest("缩略图超过 512KB")
        // Content-addressed + idempotent: put() skips when the thumb already exists.
        storage.put(thumbKey(meta.sha256), ByteReadChannel(bytes))
        // Same content => same cover: flag every row that references this sha256.
        withContext(Dispatchers.IO) {
            transaction(DatabaseFactory.db) {
                FilesTable.update({ (FilesTable.sha256 eq meta.sha256) and (FilesTable.user eq meta.userId) }) {
                    it[hasThumbnail] = true
                }
            }
        }
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
        // Content-Length is safe. Content-addressed => immutable, cache forever.
        val bytes = withContext(Dispatchers.IO) { storage.get(thumbKey(meta.sha256)).toInputStream().readBytes() }
        call.response.header(HttpHeaders.ETag, meta.sha256)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
        call.respondBytes(bytes, ContentType.Image.JPEG)
    }
}

/** Streamed outgoing body used by file/thumbnail content responses. */
internal class FileStream(
    private val channel: ByteReadChannel,
    override val contentLength: Long,
    override val contentType: ContentType?,
) : OutgoingContent.ReadChannelContent() {
    override fun readFrom(): ByteReadChannel = channel
}
