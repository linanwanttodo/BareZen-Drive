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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Drives the whole upload pipeline for one file at a time (v0.0.1: a new
 * upload simply overwrites the single progress state; concurrent uploads are
 * out of scope until the UI needs them):
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
 *    to /thumbnail. Any failure here is swallowed - the file itself is
 *    already uploaded; the cover only upgrades list tiles to previews.
 *    Instant uploads skip this step: a cover for the same content hash is
 *    already stored server-side.
 *
 * Progress is exposed as a single StateFlow<Progress> with a phase enum;
 * hashing reports the streamed byte count, uploading reports the cumulative
 * uploaded bytes (received chunks count too), completing/done are terminal.
 * On cancellation (coroutine cancelled mid-upload) the server session is
 * aborted best-effort inside NonCancellable, then CancellationException is
 * rethrown. Backoff base is constructor-injectable so tests can use a small
 * value.
 */
class UploadManager(
    private val api: UploadApi,
    private val backoffBaseMs: Long = 200L,
    private val coverGen: suspend (PickedFile) -> ByteArray? = ::generateCover,
) {
    enum class Phase { IDLE, HASHING, UPLOADING, COMPLETING, COVER, DONE, FAILED }

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

    private val _progress = MutableStateFlow(Progress(Phase.IDLE, "", 0L, 0L))
    val progress: StateFlow<Progress> = _progress

    private var activeUploadId: String? = null

    suspend fun upload(file: PickedFile, folderId: String?): Result<FileDto> {
        // Mirror every upload into the transfer centre so the UI can show
        // progress and history instead of a modal.
        val transferId = com.linan.barezen_drive.data.transfer.TransferCenter
            .start(file.name, com.linan.barezen_drive.data.transfer.TransferKind.UPLOAD, file.size)
        try {
            val result = doUpload(file, folderId, transferId)
            result.fold(
                onSuccess = { com.linan.barezen_drive.data.transfer.TransferCenter.done(transferId) },
                onFailure = { com.linan.barezen_drive.data.transfer.TransferCenter.fail(transferId, it.message ?: "failed") },
            )
            return result
        } catch (e: CancellationException) {
            com.linan.barezen_drive.data.transfer.TransferCenter.fail(transferId, "cancelled")
            // Best-effort cleanup so the server does not keep an orphaned
            // session; must not swallow the cancellation itself.
            activeUploadId?.let { id ->
                runCatching { withContext(NonCancellable) { api.abort(id) } }
            }
            throw e
        } finally {
            activeUploadId = null
        }
    }

    private suspend fun doUpload(file: PickedFile, folderId: String?, transferId: String): Result<FileDto> {
        // 1) Whole-file SHA-256, streamed in fixed-size reads.
        _progress.value = Progress(Phase.HASHING, file.name, 0L, file.size)
        val hasher = Sha256er.newInstance()
        var hashed = 0L
        while (hashed < file.size) {
            val bytes = file.readRange(hashed, HASH_READ_BYTES) ?: break
            hasher.update(bytes)
            hashed += bytes.size
            _progress.value = Progress(Phase.HASHING, file.name, hashed, file.size)
            com.linan.barezen_drive.data.transfer.TransferCenter.progress(transferId, hashed / 2, file.size)
        }
        val wholeSha = hasher.digestHex()

        // 2) Init: server may match the hash (instant upload) or report
        //    already-received chunk indexes (resume of a previous session).
        _progress.value = Progress(Phase.UPLOADING, file.name, 0L, file.size)
        com.linan.barezen_drive.data.transfer.TransferCenter.progress(transferId, file.size / 2, file.size)
        val init = api.init(
            UploadInitRequest(
                folderId = folderId,
                name = file.name,
                size = file.size,
                mimeType = file.mimeType,
                sha256 = wholeSha,
            ),
        ).getOrElse { e -> return failed(file, 0L, e) }
        if (init.instantUpload) {
            // A malformed server response (instantUpload without a file payload) must
            // surface as Result.failure, not escape as an exception.
            val created = init.file
                ?: return failed(file, 0L, ApiFailure.Network("instantUpload response missing file payload"))
            _progress.value = Progress(Phase.DONE, file.name, file.size, file.size)
            return Result.success(created)
        }

        // Track the session so a cancellation mid-chunks can abort it.
        activeUploadId = init.uploadId

        val chunkSize = init.chunkSize.coerceAtLeast(1L)
        val expectedChunks = ((file.size + chunkSize - 1) / chunkSize).toInt()

        // 3) Sequential chunks in index order, skipping received indexes.
        for (index in 0 until expectedChunks) {
            val offset = index.toLong() * chunkSize
            val length = minOf(chunkSize, file.size - offset).toInt()
            val doneBytes = minOf((index + 1).toLong() * chunkSize, file.size)
            if (index in init.receivedChunks) {
                _progress.value = Progress(Phase.UPLOADING, file.name, doneBytes, file.size)
                continue
            }
            val bytes = file.readRange(offset, length)
                ?: return failed(file, doneBytes, ApiFailure.Network("readRange returned null at offset $offset"))
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
                    return failed(file, doneBytes, result.exceptionOrNull() ?: ApiFailure.Network("chunk $index failed"))
                }
                delay(backoffBaseMs * (1L shl (attempt - 1)))
            }
            _progress.value = Progress(Phase.UPLOADING, file.name, doneBytes, file.size)
        }

        // 4) Complete.
        activeUploadId = init.uploadId
        _progress.value = Progress(Phase.COMPLETING, file.name, file.size, file.size)
        val dto = api.complete(init.uploadId).getOrElse { e ->
            _progress.value = Progress(Phase.FAILED, file.name, file.size, file.size, e)
            return Result.failure(e)
        }

        // 5) Best-effort cover upload; never fails the upload itself. Always try
        // for supported types: the row flag may be stale for same-content files
        // and the server-side PUT is idempotent (skips existing covers).
        _progress.value = Progress(Phase.COVER, file.name, file.size, file.size)
        val cover = runCatching { coverGen(file) }.getOrNull()
        if (cover != null) {
            runCatching { api.putThumbnail(dto.id, cover) }
        }
        _progress.value = Progress(Phase.DONE, file.name, file.size, file.size)
        return Result.success(dto)
    }

    private fun failed(file: PickedFile, bytesDone: Long, error: Throwable): Result<FileDto> {
        _progress.value = Progress(Phase.FAILED, file.name, bytesDone, file.size, error)
        return Result.failure(error)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val HASH_READ_BYTES = 1 shl 20 // 1 MiB streaming reads for the whole-file hash
    }
}
