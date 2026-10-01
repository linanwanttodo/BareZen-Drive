package com.linan.barezen_drive.webdav

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.files.FileMeta
import com.linan.barezen_drive.files.respondStoredFile
import com.linan.barezen_drive.storage.StorageRegistry
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.fromHttpToGmtDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.method
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * GET and HEAD for a resource path.
 *
 * The body itself is not written here. `respondStoredFile` already owns the
 * streaming, the Range/206/416 answers, the safe content-type policy and the
 * pruned-blob 404, and it is the same responder the app's own content routes
 * use - a second copy of those decisions is exactly how the two drifted before
 * phase one extracted it. Everything this file adds is what a responder cannot
 * know: which resource the path names, and whether the client already has it.
 */
internal fun Route.davGetAndHead() {
    get { call.respondDavRead() }
    // HEAD is the same handler behind a different registration: Ktor's method
    // selector is an exact match, and its engine streams whatever the handler
    // produces, so the body has to be dropped on the way out rather than by a
    // second, divergent copy of the read path. `method(...)` rather than
    // `head { }` only because the plugin has to be installed on the node that
    // holds the handler - `head { }` hands over the handler and nothing else.
    method(HttpMethod.Head) {
        install(DavAutoHead)
        handle { call.respondDavRead() }
    }
}

private val DavAutoHead = createRouteScopedPlugin("DavAutoHead") {
    /**
     * The body a HEAD response must carry: none.
     *
     * `contentLength` and the headers come straight from the content the GET
     * would have produced, so the two cannot disagree about the size, the type
     * or `Accept-Ranges` - and because the blob is only opened by `readFrom`,
     * which this never calls, a client asking only for the size does not pay for
     * the bytes. Same shape as Ktor's own head handling for static files.
     */
    class HeadBody(private val original: OutgoingContent) : OutgoingContent.NoContent() {
        override val status: HttpStatusCode? get() = original.status
        override val contentType: ContentType? get() = original.contentType
        override val contentLength: Long? get() = original.contentLength
        override val headers: Headers get() = original.headers
    }

    on(ResponseBodyReadyForSend) { _, content ->
        // NoContent already has no body (304, 412); wrapping it would only
        // replace the 0 Content-Length those answers rely on.
        if (content is OutgoingContent.NoContent) return@on
        transformBodyTo(HeadBody(content))
    }
}

/** What the conditional headers of one request decided. */
internal sealed interface DavPrecondition {
    /** No condition, or every one of them held: perform the method. */
    data object Proceed : DavPrecondition

    /** The client already has this representation: 304, and no body. */
    data object NotModified : DavPrecondition

    /** The client insisted on a representation we no longer have: 412. */
    data object Failed : DavPrecondition
}

/**
 * Error code a failed `If-Match` / `If-Unmodified-Since` answers with.
 *
 * Internal rather than private because the write methods refuse on the same
 * [davPrecondition] and must answer with the same code, or a client cannot tell
 * "your validator is stale" from "the server does not implement preconditions".
 */
internal const val DAV_PRECONDITION_FAILED = "dav-precondition-failed"

/** Children a collection GET lists before it says the listing is truncated. */
private const val DAV_INDEX_PAGE = 200

internal suspend fun ApplicationCall.respondDavRead() {
    davRequireQuota()
    // GET and HEAD are reads, so a read-only mount passes davWritable() - which
    // is deliberately not called: it exists to refuse writes, not to gate reads.
    val userId = requireDavToken().userId
    // A path that does not decode, or that hides a separator inside a segment,
    // cannot name anything: no name may contain one.
    val segments = davSegments(request.path().removePrefix("/dav"))
        ?: throw ApiException.notFound("资源不存在")

    when (val target = resolveDavPath(userId, segments)) {
        DavTarget.Missing -> throw ApiException.notFound("资源不存在")
        is DavTarget.Blob -> respondDavBlob(userId, target.id)
        is DavTarget.Collection -> respondDavIndex(userId, target.id, segments)
    }
}

private suspend fun ApplicationCall.respondDavBlob(userId: UUID, fileId: UUID) {
    val row = withContext(Dispatchers.IO) { davBlobRow(userId, fileId) }
        ?: throw ApiException.notFound("资源不存在")

    // The digest, which is also the last segment of the blob key and the value
    // PROPFIND reports as getetag - content-addressed storage makes it a strong
    // validator for free, and using the key itself would leak blobs/ab/cd to
    // every client that reads a header.
    val etag = "\"${row.sha256}\""
    when (davPrecondition(etag, row.updatedAt)) {
        DavPrecondition.Proceed -> Unit
        DavPrecondition.NotModified -> {
            response.header(HttpHeaders.ETag, etag)
            response.header(HttpHeaders.LastModified, DavProperties.httpDate(row.updatedAt))
            respond(HttpStatusCode.NotModified)
            return
        }
        // Refused before the row is even probed for its payload: a 412 says
        // nothing about the bytes, and the same gate guards PUT, where running
        // anything first would leave a half-written file behind.
        DavPrecondition.Failed -> throw ApiException(
            DAV_PRECONDITION_FAILED, HttpStatusCode.PreconditionFailed,
            "资源的 ETag 已变化，请重新读取后再试",
        )
    }

    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.LastModified, DavProperties.httpDate(row.updatedAt))
    val (storage, key) = StorageRegistry.resolve(row.meta.storageKey)
    respondStoredFile(storage, row.meta.copy(storageKey = key))
}

/**
 * A minimal browsable index for a collection.
 *
 * Clients do GET a collection sometimes (Finder's "open in browser", a stray
 * paste), and a 500 there reads as "the mount is broken". PROPFIND remains the
 * listing of record; this page is bounded to one page and says so when it is
 * cut short rather than quietly showing a partial directory as a whole one.
 */
private suspend fun ApplicationCall.respondDavIndex(
    userId: UUID,
    folderId: UUID?,
    segments: List<String>,
) {
    val page = withContext(Dispatchers.IO) {
        DavListing.page(userId, folderId, null, null, limit = DAV_INDEX_PAGE)
    }
    val base = segments.joinToString(prefix = "/dav", separator = "") { "/" + DavProperties.hrefSegment(it) }
    val title = segments.lastOrNull() ?: "BareZen Drive"
    respondText(
        text = buildString {
            append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>")
            append(DavProperties.xmlEscaped(title)).append("</title></head><body>\n<h1>")
            append(DavProperties.xmlEscaped(title)).append("</h1>\n")
            if (segments.isNotEmpty()) {
                val parent = segments.dropLast(1)
                    .joinToString(prefix = "/dav", separator = "") { "/" + DavProperties.hrefSegment(it) }
                append("<p><a href=\"").append(parent).append("/\">../</a></p>\n")
            }
            append("<ul>\n")
            for (entry in page) {
                append("<li><a href=\"").append(base).append('/')
                    .append(DavProperties.hrefSegment(entry.name))
                if (entry.collection) append('/')
                append("\">").append(DavProperties.xmlEscaped(entry.name))
                if (entry.collection) append('/')
                append("</a></li>\n")
            }
            append("</ul>\n")
            if (page.size >= DAV_INDEX_PAGE) {
                append("<p>listing truncated - use PROPFIND for the whole directory</p>\n")
            }
            append("</body></html>\n")
        },
        contentType = ContentType.parse("text/html; charset=utf-8"),
        status = HttpStatusCode.OK,
    )
}

/** The row a mount serves, with the timestamp the conditional headers compare. */
private class DavBlobRow(val meta: FileMeta, val updatedAt: Long) {
    val sha256: String get() = meta.sha256
}

private fun davBlobRow(userId: UUID, fileId: UUID): DavBlobRow? =
    transaction(DatabaseFactory.db) {
        FilesTable.select(
            FilesTable.id, FilesTable.user, FilesTable.folder, FilesTable.name, FilesTable.size,
            FilesTable.mimeType, FilesTable.sha256, FilesTable.storageKey, FilesTable.hasThumbnail,
            FilesTable.updatedAt,
        ).where {
            (FilesTable.id eq fileId) and (FilesTable.user eq userId) and (FilesTable.deletedAt eq 0L)
        }.firstOrNull()?.let { r ->
            DavBlobRow(
                FileMeta(
                    r[FilesTable.id], r[FilesTable.user], r[FilesTable.folder], r[FilesTable.name],
                    r[FilesTable.size], r[FilesTable.mimeType], r[FilesTable.sha256], r[FilesTable.storageKey],
                    r[FilesTable.hasThumbnail],
                ),
                r[FilesTable.updatedAt],
            )
        }
    }

/**
 * RFC 9110 §13.2.2, in order: `If-Match`, then `If-Unmodified-Since` only when
 * there was no `If-Match`; then `If-None-Match`, then `If-Modified-Since` only
 * when there was no `If-None-Match`. Evaluating a later header after an earlier
 * one already decided would let a client be told 304 for a representation the
 * same request just declared it would refuse to overwrite.
 *
 * Shared with the write methods on purpose: the whole point of an `If-Match` on
 * a PUT is that the refusal happens before anything is written.
 */
internal fun ApplicationCall.davPrecondition(etag: String?, lastModified: Long?): DavPrecondition {
    val ifMatch = request.headers[HttpHeaders.IfMatch]
    if (!ifMatch.isNullOrBlank()) {
        if (!ifMatch.davEtagHolds(etag, weak = false)) return DavPrecondition.Failed
    } else {
        val since = request.headers[HttpHeaders.IfUnmodifiedSince]?.let(::davHttpDateOrNull)
        if (since != null && lastModified != null && lastModified.davWholeSeconds() > since.davWholeSeconds()) {
            return DavPrecondition.Failed
        }
    }
    val ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
    if (!ifNoneMatch.isNullOrBlank()) {
        if (ifNoneMatch.davEtagHolds(etag, weak = true)) return DavPrecondition.NotModified
    } else {
        val since = request.headers[HttpHeaders.IfModifiedSince]?.let(::davHttpDateOrNull)
        if (since != null && lastModified != null && lastModified.davWholeSeconds() <= since.davWholeSeconds()) {
            return DavPrecondition.NotModified
        }
    }
    return DavPrecondition.Proceed
}

/**
 * An entity-tag list against the one representation there is.
 *
 * `*` matches any existing resource, and a weak tag never satisfies `If-Match`
 * (which compares strongly, per RFC 9110 §8.8.3.2) while it always satisfies
 * `If-None-Match` (weak comparison). Our tag is always strong, so this only ever
 * has to cope with what clients send back.
 */
private fun String.davEtagHolds(actual: String?, weak: Boolean): Boolean {
    val tags = split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (tags.contains("*")) return actual != null
    if (actual == null) return false
    return tags.any { tag ->
        val weakTag = tag.startsWith("W/")
        (!weakTag || weak) && tag.removePrefix("W/") == actual
    }
}

/** Epoch millis floored to whole seconds, which is all an HTTP date carries. */
private fun Long.davWholeSeconds(): Long = this / 1000L * 1000L

/** An unparseable date is ignored rather than fatal (RFC 9110 §5.6.1). */
private fun davHttpDateOrNull(value: String): Long? =
    runCatching { value.trim().fromHttpToGmtDate().timestamp }.getOrNull()