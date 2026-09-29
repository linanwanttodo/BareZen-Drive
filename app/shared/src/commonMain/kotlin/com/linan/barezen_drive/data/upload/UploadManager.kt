package com.linan.barezen_drive.data.upload

import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.UploadInitRequest
import com.linan.barezen_drive.data.api.ApiFailure
import com.linan.barezen_drive.data.repo.UploadApi
import com.linan.barezen_drive.platform.PickedFile
import com.linan.barezen_drive.platform.Sha256er
import com.linan.barezen_drive.platform.generateCover
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Drives the whole upload pipeline for one file per call:
 *
 * 1. whole-file SHA-256, streamed through Sha256er over PickedFile.readRange
 *    chunks (the file is never fully loaded into memory);
 * 2. uploadInit - the server may answer instantUpload (file already exists,
 *    nothing to send) or report already received chunk indexes for resume;
 * 3. sequential chunk upload in index order, skipping received indexes, with
 *    up to MAX_ATTEMPTS per chunk and exponential backoff
 *    (backoffBaseMs * 2^n between attempts); a chunk that exhausts its
 *    attempts fails the whole upload and the server session is aborted;
 * 4. uploadComplete, which returns the created FileDto.
 * 5. cover generation (images/videos): the client-side generated JPEG is PUT
 *    to /thumbnail. Any failure here is logged and swallowed - the file itself
 *    is already uploaded; the cover only upgrades list tiles to previews.
 *    Instant uploads skip this step: a cover for the same content hash is
 *    already stored server-side.
 *
 * ## Progress: per call, never a shared slot
 *
 * There is no manager-wide progress state. A single `_progress` field plus an
 * `activeUploadId` used to live here, and both were single-slot: two uploads
 * on one instance overwrote each other, so the UI showed one file's bar for
 * another and cancelling the first aborted the second's server session.
 * Progress is now per call through [upload]'s `onProgress`, and the session id
 * a cancellation must abort is a local of the call that opened it. The
 * durable, multi-row view the pages render is TransferCenter's, which already
 * models one row per transfer; the manager only writes to it.
 *
 * On cancellation (coroutine cancelled mid-upload) that call's own server
 * session is aborted best-effort inside NonCancellable, then
 * CancellationException is rethrown. Backoff base is constructor-injectable so
 * tests can use a small value.
 */
class UploadManager(
    private val api: UploadApi,
    private val backoffBaseMs: Long = 200L,
    private val coverGen: suspend (PickedFile) -> ByteArray? = ::generateCover,
    /**
     * Where the cover pipeline's failures go. Both halves used to be a bare
     * `getOrNull()`, so "uploaded, but it never got a cover" - the exact case
     * nobody could chase - left no trace at all, and a local decoder failure
     * was indistinguishable from a rejected PUT.
     *
     * Default is stdout: a cover is best-effort, so this must never be fatal.
     * Tests pass a collector to assert on the two stages separately.
     */
    private val log: (String) -> Unit = { println(it) },
) {
    enum class Phase { HASHING, UPLOADING, COMPLETING, COVER, DONE, FAILED }

    data class Progress(
        val phase: Phase,
        val fileName: String,
        val bytesDone: Long,
        val bytesTotal: Long,
        val error: Throwable? = null,
    ) {
        /** 0..1 byte fraction; 0 when bytesTotal is 0 (empty/unknown file). */
        val fraction: Double
            get() = if (bytesTotal <= 0L) 0.0 else (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0)
    }

    suspend fun upload(
        file: PickedFile,
        folderId: String?,
        /** False during album-sync batches: the batch reports one aggregate transfer. */
        reportTransfer: Boolean = true,
        /** A previously computed whole-file SHA-256, when the caller has one
         *  cached. Skipping the re-hash is the whole point of the sync queue's
         *  `hash_cache` column: a retried photo no longer reads 50 MB to re-derive
         *  a value the server already accepted. */
        cachedSha256: String? = null,
        /** Invoked once with the freshly computed hash so the caller can persist
         *  it for the next retry. Not called when [cachedSha256] was supplied. */
        onHashed: ((String) -> Unit)? = null,
        /** Replace a same-named file instead of failing with NAME_CONFLICT; the
         *  server keeps the previous content as a restorable version. */
        overwrite: Boolean = false,
        /** Which transfer page lists this upload: album picks report to the
         *  album lane, everything else lands on the file transfer page. */
        lane: com.linan.barezen_drive.data.transfer.TransferLane =
            com.linan.barezen_drive.data.transfer.TransferLane.FILE,
        /** This call's own progress. A caller driving one file at a time can
         *  render it directly; anything that lists several uploads at once
         *  (the files page) reads TransferCenter's rows instead, which is the
         *  projection that survives this call returning. */
        onProgress: ((Progress) -> Unit)? = null,
    ): Result<FileDto> {
        // Mirror every upload into the transfer centre so the UI can show
        // progress and history instead of a modal. Album-sync batches pass
        // reportTransfer=false: the batch owns one aggregate entry, so a
        // hundred-photo backup does not spam a hundred rows/notifications.
        val transferId = if (reportTransfer) {
            com.linan.barezen_drive.data.transfer.TransferCenter
                .start(file.name, com.linan.barezen_drive.data.transfer.TransferKind.UPLOAD, file.size, lane)
        } else {
            null
        }
        // Keep the source beside the row so a failed single-file upload can be
        // retried from the transfer centre. Album batches report one aggregate
        // row for a whole queue, so they keep no handle (the sync worker owns
        // its own source list and retries on its next pass).
        transferId?.let {
            com.linan.barezen_drive.data.transfer.TransferCenter.rememberRetryHandle(
                it,
                com.linan.barezen_drive.data.transfer.RetryHandle(file, folderId, overwrite),
            )
        }
        // The session THIS call opened. It was an instance field, so a second
        // upload on the same manager overwrote it and the cancellation below
        // aborted the wrong server session.
        var sessionId: String? = null
        try {
            val result = doUpload(file, folderId, transferId, cachedSha256, onHashed, overwrite, onProgress) {
                sessionId = it
            }
            if (transferId != null) {
                result.fold(
                    onSuccess = { com.linan.barezen_drive.data.transfer.TransferCenter.done(transferId, it.id) },
                    onFailure = { com.linan.barezen_drive.data.transfer.TransferCenter.fail(transferId, it.message ?: "failed") },
                )
            }
            return result
        } catch (e: CancellationException) {
            // Cancellation must abort the server session even when the upload
            // is not mirrored in the transfer centre (batch mode): previously
            // only the mirrored path cleaned up, leaking orphaned sessions.
            if (transferId != null) {
                com.linan.barezen_drive.data.transfer.TransferCenter.fail(transferId, "cancelled")
            }
            sessionId?.let { id ->
                runCatching { withContext(NonCancellable) { api.abort(id) } }
            }
            throw e
        }
    }

    /**
     * Re-runs a failed single-file upload from the transfer centre.
     *
     * The row that failed is updated in place instead of spawning a second
     * one: the user clicked "retry" on that row, so a new row would just be a
     * duplicate of the same work. Returns null when the row is not retryable
     * (album batch, or the row was cleared in the meantime).
     */
    suspend fun retryFailed(
        transferId: String,
        onProgress: ((Progress) -> Unit)? = null,
    ): Result<FileDto>? {
        val handle = com.linan.barezen_drive.data.transfer.TransferCenter.retryHandle(transferId) ?: return null
        com.linan.barezen_drive.data.transfer.TransferCenter.progress(transferId, 0, handle.file.size, reset = true)
        com.linan.barezen_drive.data.transfer.TransferCenter.clearError(transferId)
        // Same per-call rule as upload(): a retry owns the session it opens, so
        // cancelling it aborts that session and nothing else.
        var sessionId: String? = null
        return try {
            val result = doUpload(
                handle.file, handle.folderId, transferId, null, null, handle.overwrite, onProgress,
            ) { sessionId = it }
            result.fold(
                onSuccess = { com.linan.barezen_drive.data.transfer.TransferCenter.done(transferId, it.id) },
                onFailure = { com.linan.barezen_drive.data.transfer.TransferCenter.fail(transferId, it.message ?: "failed") },
            )
            result
        } catch (e: CancellationException) {
            com.linan.barezen_drive.data.transfer.TransferCenter.fail(transferId, "cancelled")
            sessionId?.let { id -> runCatching { withContext(NonCancellable) { api.abort(id) } } }
            throw e
        }
    }

    private suspend fun doUpload(
        file: PickedFile,
        folderId: String?,
        transferId: String?,
        cachedSha256: String?,
        onHashed: ((String) -> Unit)?,
        overwrite: Boolean,
        onProgress: ((Progress) -> Unit)?,
        /** Hands the freshly opened server session back to the caller so only
         *  that call can abort it. */
        onSession: (String) -> Unit,
    ): Result<FileDto> {
        // 1) Whole-file SHA-256, streamed in fixed-size reads - unless the caller
        // already has a valid cached hash for this exact (uri, size, mtime), in
        // which case the file is unchanged and re-hashing is pure wasted I/O.
        onProgress?.invoke(Progress(Phase.HASHING, file.name, 0L, file.size))
        val wholeSha = if (cachedSha256 != null) {
            transferId?.let { com.linan.barezen_drive.data.transfer.TransferCenter.progress(it, file.size / 2, file.size) }
            cachedSha256
        } else {
            val hasher = Sha256er.newInstance()
            var hashed = 0L
            while (hashed < file.size) {
                val bytes = file.readRange(hashed, HASH_READ_BYTES) ?: break
                hasher.update(bytes)
                hashed += bytes.size
                onProgress?.invoke(Progress(Phase.HASHING, file.name, hashed, file.size))
                transferId?.let { com.linan.barezen_drive.data.transfer.TransferCenter.progress(it, hashed / 2, file.size) }
            }
            hasher.digestHex().also { onHashed?.invoke(it) }
        }

        // 2) Init: server may match the hash (instant upload) or report
        //    already-received chunk indexes (resume of a previous session).
        onProgress?.invoke(Progress(Phase.UPLOADING, file.name, 0L, file.size))
        transferId?.let { com.linan.barezen_drive.data.transfer.TransferCenter.progress(it, file.size / 2, file.size) }
        val init = api.init(
            UploadInitRequest(
                folderId = folderId,
                name = file.name,
                size = file.size,
                mimeType = file.mimeType,
                sha256 = wholeSha,
                takenAt = file.originDateMs,
                overwrite = overwrite,
            ),
        ).getOrElse { e -> return failed(file, 0L, e, onProgress) }
        if (init.instantUpload) {
            // A malformed server response (instantUpload without a file payload) must
            // surface as Result.failure, not escape as an exception.
            val created = init.file
                ?: return failed(file, 0L, ApiFailure.Network("instantUpload response missing file payload"), onProgress)
            onProgress?.invoke(Progress(Phase.DONE, file.name, file.size, file.size))
            return Result.success(created)
        }

        // Track the session so a cancellation mid-chunks can abort it.
        onSession(init.uploadId)

        val chunkSize = init.chunkSize.coerceAtLeast(1L)
        val expectedChunks = ((file.size + chunkSize - 1) / chunkSize).toInt()

        // 3) Sequential chunks in index order, skipping received indexes.
        // Hashing already consumed the first half of the transfer-centre
        // scale (hashed / 2), so byte progress maps into 50..100% here and
        // never runs backwards.
        for (index in 0 until expectedChunks) {
            val offset = index.toLong() * chunkSize
            val length = minOf(chunkSize, file.size - offset).toInt()
            val doneBytes = minOf((index + 1).toLong() * chunkSize, file.size)
            if (index in init.receivedChunks) {
                onProgress?.invoke(Progress(Phase.UPLOADING, file.name, doneBytes, file.size))
                transferId?.let { com.linan.barezen_drive.data.transfer.TransferCenter.progress(it, file.size / 2 + doneBytes / 2, file.size) }
                continue
            }
            val bytes = file.readRange(offset, length)
                ?: return failed(file, doneBytes, ApiFailure.Network("readRange returned null at offset $offset"), onProgress)
            val chunkSha = Sha256er.newInstance().apply { update(bytes) }.digestHex()
            var attempt = 0
            while (true) {
                attempt++
                val result = api.putChunk(init.uploadId, index, bytes, chunkSha)
                if (result.isSuccess) break
                if (attempt >= MAX_ATTEMPTS) {
                    // Give up: surface the failure and abort the session so no
                    // partial upload is left behind server-side.
                    runCatching { api.abort(init.uploadId) }
                    return failed(
                        file, doneBytes, result.exceptionOrNull() ?: ApiFailure.Network("chunk $index failed"),
                        onProgress,
                    )
                }
                delay((backoffBaseMs * (1L shl (attempt - 1))).milliseconds)
            }
            onProgress?.invoke(Progress(Phase.UPLOADING, file.name, doneBytes, file.size))
            transferId?.let { com.linan.barezen_drive.data.transfer.TransferCenter.progress(it, file.size / 2 + doneBytes / 2, file.size) }
        }

        // 4) Complete.
        onSession(init.uploadId)
        onProgress?.invoke(Progress(Phase.COMPLETING, file.name, file.size, file.size))
        val dto = api.complete(init.uploadId).getOrElse { e ->
            onProgress?.invoke(Progress(Phase.FAILED, file.name, file.size, file.size, e))
            return Result.failure(e)
        }

        // 5) Best-effort cover upload; never fails the upload itself. Always try
        // for supported types: the row flag may be stale for same-content files
        // and the server-side PUT is idempotent (skips existing covers).
        //
        // The two stages are logged apart on purpose: a decoder that cannot
        // handle the file and a server that rejected the PUT need completely
        // different fixes, and one merged "cover failed" line told the reader
        // nothing about which one happened.
        onProgress?.invoke(Progress(Phase.COVER, file.name, file.size, file.size))
        val cover = runCatching { coverGen(file) }
            .onFailure { e -> log("cover gen failed for ${file.name}: ${e.message ?: e.toString()}") }
            .getOrNull()
        if (cover != null) {
            runCatching { api.putThumbnail(dto.id, cover).getOrThrow() }
                .onFailure { e -> log("cover put failed for ${dto.id}: ${e.message ?: e.toString()}") }
            // Direct-to-cache: the list surfaces show this cover on the next
            // frame instead of refetching it from the server one tile at a time.
            com.linan.barezen_drive.ui.media.ThumbnailHub.put(dto.id, cover)
        }
        onProgress?.invoke(Progress(Phase.DONE, file.name, file.size, file.size))
        return Result.success(dto)
    }

    private fun failed(
        file: PickedFile,
        bytesDone: Long,
        error: Throwable,
        onProgress: ((Progress) -> Unit)?,
    ): Result<FileDto> {
        onProgress?.invoke(Progress(Phase.FAILED, file.name, bytesDone, file.size, error))
        return Result.failure(error)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val HASH_READ_BYTES = 1 shl 20 // 1 MiB streaming reads for the whole-file hash
    }
}
