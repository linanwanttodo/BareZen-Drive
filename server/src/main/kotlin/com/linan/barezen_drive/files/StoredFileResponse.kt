package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*

/**
 * Streamed body for a blob held by the storage provider.
 *
 * Two things it does that a plain `FileStream(channel, ...)` cannot, both of
 * them about *when* the blob is opened and *when* it is released:
 *
 *  - The channel is created inside the writing coroutine, not before
 *    `respond`. ktor's PartialContent plugin validates `Range` after the
 *    outgoing content exists and answers an unsatisfiable one by swapping the
 *    body for a 416 - it never reads the original channel, so anything opened
 *    up front is a file descriptor that nobody closes. Deferring the open
 *    means the 416 path (and any other rejection) never touches the disk.
 *  - The source is cancelled in a `finally`, which covers the range path too.
 *    ktor's default `ReadChannelContent.readFrom(range)` copies the source
 *    into a second channel and never closes the source, so a 206 that stops
 *    mid-file - the normal shape of a media seek - would otherwise leave the
 *    descriptor open until the GC got round to it.
 */
internal class StoredFileContent(
    private val storage: StorageProvider,
    private val key: String,
    override val contentLength: Long,
    override val contentType: ContentType?,
) : OutgoingContent.ReadChannelContent() {

    override fun readFrom(): ByteReadChannel = pipe(null)

    override fun readFrom(range: LongRange): ByteReadChannel =
        if (range.isEmpty()) ByteReadChannel.Empty else pipe(range)

    private fun pipe(range: LongRange?): ByteReadChannel = COPY_SCOPE.writer(autoFlush = true) {
        val source = open()
        try {
            if (range == null) {
                source.copyTo(channel)
            } else {
                source.discard(range.first)
                source.copyTo(channel, range.last - range.first + 1)
            }
        } finally {
            // Normal completion, a failed copy and a cancelled write all land
            // here; the storage channel is the thing that owns the descriptor.
            source.cancel()
        }
    }.channel

    private suspend fun open(): ByteReadChannel = withContext(Dispatchers.IO) { storage.get(key) }

    private companion object {
        /**
         * The copy must not run on the caller's dispatcher and must not be a
         * child of the request job: ktor writes the body after the route
         * handler has returned, and a cancelled request still has to drain (or
         * abort) the channel it was handed. Each writer coroutine is bounded by
         * the response and releases the blob in its own `finally`, so a shared
         * supervisor job is all the scope this needs.
         */
        val COPY_SCOPE: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}

/**
 * Writes a stored file to [call], applying the shared content-type policy.
 *
 * Both public content routes - the signed/authenticated
 * `/api/files/{id}/content` and the token-only
 * `/api/public/shares/{token}/files/{fid}/content` - stream the same bytes for
 * the same row, so the decisions that decide what a browser does with them
 * (inline vs attachment, 404 vs 500 on a pruned payload, whether a rejected
 * range ever opens the blob) live here once. Two hand-written copies of these
 * three steps had already drifted: a fix applied to one of them silently left
 * the other, more exposed, route unchanged.
 *
 * Range handling stays with the installed PartialContent plugin: no `Range`
 * header is a plain 200, a byte range becomes 206 + `Content-Range`, and an
 * unsatisfiable one becomes 416 with a total-length-only Content-Range.
 */
internal suspend fun ApplicationCall.respondStoredFile(storage: StorageProvider, meta: FileMeta) {
    // The stored mimeType came from the uploading client, so it cannot be
    // served back as-is: anything a browser can execute (html, svg, js) has
    // to leave as a download. Media and PDF stay inline for the viewer.
    // See safeFileContent for the whitelist and the reasoning.
    val safe = safeFileContent(meta.mimeType, meta.name)
    safe.disposition?.let { response.headers.append(HttpHeaders.ContentDisposition, it) }
    // exists() guard: the row can reference a blob that no longer exists on
    // disk (manual prune, half-completed purge); answer 404 instead of letting
    // the open fail as a 500.
    if (!withContext(Dispatchers.IO) { storage.exists(meta.storageKey) }) {
        throw ApiException.notFound("文件内容不存在")
    }
    respond(StoredFileContent(storage, meta.storageKey, meta.size, safe.type))
}
