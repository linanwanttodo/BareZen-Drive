package com.linan.barezen_drive.webdav

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.core.dto.ErrorCodes
import com.linan.barezen_drive.core.dto.UploadInitRequest
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.files.UploadService
import com.linan.barezen_drive.storage.StorageProvider
import com.linan.barezen_drive.storage.StorageRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Registers PUT on a mount.
 *
 * `put { call.respondDavPut() }` and not `put { call.respondDavPut { } }`: Ktor's
 * `put(body)` is `method(Put) { handle(body) }`, so a lambda that installs
 * another handler produces a route that never runs and falls through to the
 * catch-all 404 - which looks exactly like "PUT is not implemented".
 */
internal fun Route.davPut() {
    put { call.respondDavPut() }
}

/**
 * PUT, answered with the status the RFC asks for.
 *
 * A new resource is 201, a replaced one 204 with no body: a client that just
 * overwrote a file has nothing to read back and re-downloading the bytes it just
 * sent is pure waste on a 5 GB video.
 */
internal suspend fun ApplicationCall.respondDavPut() {
    try {
        davPut()
    } catch (cause: ApiException) {
        mapDavException(cause)
    }
}

/**
 * Translate a refusal into the status a WebDAV client expects.
 *
 * Only the size refusal differs from the REST answers. WebDAV reserves 507
 * Insufficient Storage for "the server cannot store this", and pairs it with a
 * `DAV:quota-not-exceeded` precondition element; a 413 tells a mount client the
 * request body was malformed, which is a different fault with a different fix -
 * a Windows client that reads it that way reports the file as untransferable
 * rather than retrying against a smaller quota.
 *
 * Everything else (404 / 400 / 403 / 409 / 412) is rethrown so StatusPages
 * answers it, exactly as the other DAV methods do. Mapping it here as well would
 * mean two error envelopes for one server, and the second one is not what the
 * GET and PROPFIND methods already return to the same client.
 */
private suspend fun ApplicationCall.mapDavException(cause: ApiException) {
    when (cause.status) {
        HttpStatusCode.PayloadTooLarge -> respondText(
            DAV_QUOTA_ERROR,
            ContentType.parse("application/xml; charset=utf-8"),
            HttpStatusCode.InsufficientStorage,
        )

        else -> throw cause
    }
}

/** RFC 4918 §11.1: 507 carries the precondition that failed, not prose. */
private const val DAV_QUOTA_ERROR =
    """<?xml version="1.0" encoding="utf-8"?>
<D:error xmlns:D="DAV:"><D:quota-not-exceeded/></D:error>
"""

/** A name the mount cannot address is not a name; see [davPutNameOk]. */
private const val DAV_BAD_NAME = "dav-bad-name"
private const val DAV_METHOD_NOT_ALLOWED = "dav-method-not-allowed"

/**
 * Copy buffer for the length-less PUT's measuring pass.
 *
 * Fixed rather than derived from the file size: the point of the spool is to
 * learn the size, so sizing the buffer from it would be circular.
 */
private const val DAV_SPOOL_BUFFER = 64 * 1024

internal suspend fun ApplicationCall.davPut() {
    davRequireQuota()
    val userId = davWritable().userId
    // A path that does not decode, or that hides a separator inside a segment,
    // cannot name anything: no name may contain one.
    val segments = davSegments(request.path().removePrefix("/dav"))
        ?: throw ApiException.notFound("资源不存在")
    // PUT names a resource *inside* a collection; the bare collection URL has no
    // name to create.
    if (segments.isEmpty()) {
        throw ApiException(
            DAV_METHOD_NOT_ALLOWED, HttpStatusCode.MethodNotAllowed, "集合不能作为 PUT 的目标",
        )
    }
    val name = segments.last()
    if (!davPutNameOk(name)) throw ApiException.badRequest("文件名非法", DAV_BAD_NAME)
    // 409 rather than 404 for a missing intermediate collection (RFC 4918 §9.1):
    // the request is well-formed, the path it names is not. A file used as a
    // directory is the same answer - the client must change the path, not retry.
    val parent = when (val parentTarget = resolveDavPath(userId, segments.dropLast(1))) {
        is DavTarget.Collection -> parentTarget.id
        else -> throw ApiException.conflict(ErrorCodes.NAME_CONFLICT, "父路径不存在")
    }

    when (val target = resolveDavPath(userId, segments)) {
        // 405, not 409: the resource exists and the method is simply not defined
        // for a collection. Silently replacing a folder with a file would destroy
        // its subtree.
        is DavTarget.Collection -> throw ApiException(
            DAV_METHOD_NOT_ALLOWED, HttpStatusCode.MethodNotAllowed, "集合不能作为 PUT 的目标",
        )

        is DavTarget.Blob -> putOverDav(userId, target.id, parent, name)
        DavTarget.Missing -> putNewDav(userId, parent, name)
    }
}

/**
 * Refuse before touching storage, so a stale validator costs a table lookup
 * rather than a staged file. Both outcomes of a non-`Proceed` verdict are 412
 * for a write: `If-None-Match: *` against an existing resource means "only
 * create if absent", which RFC 9110 also answers with 412, not with the 304 a
 * GET would get.
 */
private suspend fun ApplicationCall.davPutPrecondition(userId: UUID, fileId: UUID?) {
    val (etag, modified) = if (fileId == null) {
        null to null
    } else {
        withContext(Dispatchers.IO) { davBlobValidators(userId, fileId) }
            ?: throw ApiException.notFound("资源不存在")
    }
    when (davPrecondition(etag, modified)) {
        DavPrecondition.Proceed -> Unit
        DavPrecondition.NotModified, DavPrecondition.Failed -> throw ApiException(
            DAV_PRECONDITION_FAILED, HttpStatusCode.PreconditionFailed,
            "资源的 ETag 已变化，请重新读取后再试",
        )
    }
}

private suspend fun ApplicationCall.putNewDav(userId: UUID, parent: UUID?, name: String) {
    davPutPrecondition(userId, null)
    val uploaded = davWriteBody(userId, parent, name)
    callDavPutDone(uploaded.sha256, created = true)
}

private suspend fun ApplicationCall.putOverDav(
    userId: UUID,
    fileId: UUID,
    parent: UUID?,
    name: String,
) {
    davPutPrecondition(userId, fileId)
    val uploaded = davWriteBody(userId, parent, name)
    callDavPutDone(uploaded.sha256, created = false)
}

private suspend fun ApplicationCall.callDavPutDone(sha256: String, created: Boolean) {
    // The validator of what was just stored, so a client that PUTs twice can
    // send the value it received instead of re-reading the file first.
    response.header(HttpHeaders.ETag, "\"$sha256\"")
    respond(if (created) HttpStatusCode.Created else HttpStatusCode.NoContent)
}

/**
 * Stream the request body into the ordinary chunked-upload pipeline and complete
 * it, without producing a version.
 *
 * The bytes go straight into the session's `$i.part` slots on chunk boundaries,
 * one pass, and [UploadService.complete] merges them by renaming - the same two
 * passes a native chunked upload costs. The alternative (buffer the body, hash
 * it, split it, write it) is three, and holds a whole large file in memory.
 *
 * Three consequences of WebDAV that are worth stating rather than hiding:
 *
 *  - **No instant upload.** The protocol has no hook for "here is the hash
 *    first", so `clientSha256` stays null and the dedup branch of `initUpload`
 *    is unreachable from a mount. The upload pays the merge for bytes the server
 *    already holds. That is the protocol's limit, not ours.
 *  - **A length-less body (chunked transfer encoding) is spooled first**, to a
 *    temp file, to learn its size: `initUpload` needs `size` to compute how many
 *    chunks to expect. Desktop clients dragging a file send a length, so this is
 *    the fallback; it costs one extra pass over the body and nothing else.
 *  - **The declared size is the hard stop while streaming.** Nothing past
 *    `Content-Length` is ever copied, and the spool refuses at the server's file
 *    cap, so a lying or endless client cannot fill the disk - the same reason
 *    `putChunk` refuses a chunk larger than its slot.
 */
private suspend fun ApplicationCall.davWriteBody(
    userId: UUID,
    parent: UUID?,
    name: String,
): DavPutResult {
    val storage = davStorage()
    val declared = request.contentLength()
    val mime = davPutMimeType()
    val body = request.receiveChannel()

    // Declared negative means a broken or hostile framing header; it must not
    // reach `initUpload`, where a negative size would look like "empty file".
    if (declared != null && declared < 0) {
        throw ApiException.badRequest("Content-Length 非法", ErrorCodes.VALIDATION_ERROR)
    }

    var spool: File? = null
    var sessionId: UUID? = null
    try {
        val source: ByteReadChannel
        val size: Long
        if (declared != null) {
            size = declared
            source = body
        } else {
            val measured = davSpoolBody(body, storage)
            spool = measured.first
            size = measured.second
            source = FileInputStream(measured.first).toByteReadChannel()
        }

        // overwrite = true: a mount has no "rename on conflict" verb, and a
        // client saving over an existing file means exactly that. It never takes
        // the instant-upload branch, because no hash is sent.
        val init = UploadService.initUpload(
            userId,
            UploadInitRequest(
                folderId = parent?.toString(), name = name, size = size,
                mimeType = mime, chunkSize = UploadService.MAX_CHUNK, overwrite = true,
            ),
            storage,
        )
        val sid = UUID.fromString(init.uploadId)
        sessionId = sid
        // The session's own chunk size, never the one this request asked for: a
        // resumed session answers with the size its chunks were written in, and
        // splitting on a different boundary would put every part in the wrong slot.
        val chunkSize = init.chunkSize
        davStreamChunks(userId, sid, source, size, chunkSize, storage)

        val done = UploadService.complete(userId, sid, storage, snapshotOnOverwrite = false)
        return DavPutResult(done.file.sha256)
    } catch (t: Throwable) {
        // WebDAV has no abort verb, so a dropped client cannot clean up after
        // itself and the route must. The staging directory is the only thing
        // holding the bytes; the session row is swept with its TTL either way.
        sessionId?.let { runCatching { UploadService.abort(userId, it, storage) } }
        throw t
    } finally {
        spool?.delete()
    }
}

/** What a finished PUT reports back: the digest of what is now stored. */
private data class DavPutResult(val sha256: String)

/**
 * Copy [size] bytes of [body] into the session's parts, one `putChunk` per slot.
 *
 * The producer runs beside the consumer rather than buffering a whole chunk in
 * memory: `ByteChannel` applies backpressure at its 1 MiB flush window, so peak
 * memory is the window regardless of how large `chunkSize` is.
 *
 * A producer failure is captured instead of thrown from the child, because a
 * cancelled scope would replace it with a `CancellationException` and the client
 * would learn nothing. `putChunk` independently refuses a short chunk, so the
 * length verdict does not depend on which of the two notices it first.
 */
private suspend fun davStreamChunks(
    userId: UUID,
    sessionId: UUID,
    body: ByteReadChannel,
    size: Long,
    chunkSize: Long,
    storage: StorageProvider,
) {
    val expected = expectedDavChunks(size, chunkSize)
    var index = 0
    while (index < expected) {
        val slot = if (index == expected - 1) size - chunkSize * (expected - 1) else chunkSize
        coroutineScope {
            val pipe = ByteChannel(autoFlush = true)
            val producerFailure = AtomicReference<Throwable?>()
            val producer = launch {
                try {
                    body.copyTo(pipe, slot)
                } catch (t: Throwable) {
                    producerFailure.compareAndSet(null, t)
                } finally {
                    runCatching { pipe.flushAndClose() }
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    UploadService.putChunk(userId, sessionId, index, pipe, null, storage)
                }
                producer.join()
                producerFailure.get()?.let { throw it }
            } finally {
                producer.cancel()
                pipe.cancel(null)
            }
        }
        index++
    }
}

/** The session's chunk arithmetic, kept identical to `UploadService`'s. */
private fun expectedDavChunks(size: Long, chunkSize: Long): Int {
    val chunks = if (size > Long.MAX_VALUE - chunkSize) Long.MAX_VALUE else (size + chunkSize - 1) / chunkSize
    return chunks.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * Measure a body that declared no length by writing it to a temp file.
 *
 * The cap is `UploadService.maxFileSize`, checked *while* copying, so a
 * length-less body over the server's limit is refused mid-stream instead of
 * being written out in full first. Without a declared length there is nothing
 * else to stop it, and this is the one path where an endless request could
 * otherwise fill the disk.
 *
 * The temp file is named [UploadService.SPOOL_PREFIX] so the periodic stale-file
 * sweep can reclaim it after a crash: a `kill -9` mid-upload skips this
 * function's `catch` exactly as it skips `complete`'s `finally`.
 */
private suspend fun ApplicationCall.davSpoolBody(
    body: ByteReadChannel,
    storage: StorageProvider,
): Pair<File, Long> {
    val cap = UploadService.maxFileSize
    val file = withContext(Dispatchers.IO) {
        java.nio.file.Files.createTempFile(
            storage.tmpDir, UploadService.SPOOL_PREFIX, ".spool",
        ).toFile()
    }
    try {
        val written = withContext(Dispatchers.IO) {
            FileOutputStream(file).use { out ->
                val buf = ByteArray(DAV_SPOOL_BUFFER)
                var total = 0L
                while (true) {
                    val n = body.readAvailable(buf)
                    if (n < 0) break
                    total += n
                    if (total > cap) throw ApiException.tooLarge("文件超过服务器上限")
                    out.write(buf, 0, n)
                }
                total
            }
        }
        return file to written
    } catch (t: Throwable) {
        file.delete()
        throw t
    }
}

/**
 * The content type to store, or null.
 *
 * Parameters are dropped and the value is bounded: `mime_type` is a
 * varchar(255), and a client is free to send a 4 KB `Content-Type` header, which
 * would turn a successful upload into a database error. Anything unparseable or
 * oversized is stored as unknown rather than rejected - the bytes are the file.
 */
private fun ApplicationCall.davPutMimeType(): String? {
    val raw = request.headers[HttpHeaders.ContentType] ?: return null
    // Parsed rather than split, so a parameter list ("text/plain; charset=utf-8")
    // is dropped instead of being stored as part of the type - and so a malformed
    // header cannot throw out of here.
    val parsed = runCatching { ContentType.parse(raw) }.getOrNull() ?: return null
    // `ContentType.Any` is what Ktor substitutes for an absent header, and
    // application/octet-stream is what a mount sends for a file it has no type
    // for; neither says anything worth keeping.
    if (parsed == ContentType.Any || parsed.match(ContentType.Application.OctetStream)) return null
    // `toString` rather than `contentType`: in Ktor 3.5 the latter is the *type*
    // half only ("text"), while the stored column wants "text/plain". Verified by
    // parsing, not by reading the docs - this is the shape the API clients send.
    return parsed.withoutParameters().toString()
        .takeIf { it.isNotBlank() && it.length <= MIME_LIMIT }
}

private const val MIME_LIMIT = 255

/**
 * The provider this mount writes through.
 *
 * Resolved from the registry rather than injected, because the DAV routes are
 * registered without a provider (they are mounted on the routing root, not under
 * the authenticated block) and every bare key in `files.storage_key` means "the
 * backend this process started with" - which is exactly what the default is.
 */
private fun davStorage(): StorageProvider =
    StorageRegistry.defaultProvider() ?: error("no storage backend registered")

/**
 * Names a mount can address.
 *
 * `initUpload` refuses an empty name, a `/` and anything over 255 characters,
 * but not "." or ".." and not control characters. Both are hazards *here*:
 * WebDAV clients resolve `/a/../b` as `/b` before the request is even sent, so a
 * resource stored under either name can never be addressed again, and a name
 * carrying a C0 control byte turns this surface's own 207 documents into
 * something no client can parse - hiding the directory, not just the file.
 */
private fun davPutNameOk(name: String) =
    name.isNotBlank() &&
        name.length <= 255 &&
        name != "." &&
        name != ".." &&
        name.none { it.code < 0x20 || it.code == 0x7F }

/** The two values a conditional PUT compares, straight from the row. */
private fun davBlobValidators(userId: UUID, fileId: UUID): Pair<String, Long>? =
    transaction(DatabaseFactory.db) {
        // Live rows only, as everywhere else on this surface: a file trashed
        // between the path walk and here is not a resource a PUT may replace, and
        // answering with its validators would invite a client to keep trying.
        FilesTable.select(FilesTable.sha256, FilesTable.updatedAt)
            .where {
                (FilesTable.id eq fileId) and (FilesTable.user eq userId) and
                    (FilesTable.deletedAt eq 0L)
            }
            .firstOrNull()
            ?.let { "\"${it[FilesTable.sha256]}\"" to it[FilesTable.updatedAt] }
    }