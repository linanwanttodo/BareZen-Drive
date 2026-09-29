package com.linan.barezen_drive

import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.api.ApiFailure
import com.linan.barezen_drive.data.repo.UploadApi
import com.linan.barezen_drive.data.upload.UploadManager
import com.linan.barezen_drive.platform.PickedFile
import com.linan.barezen_drive.platform.Sha256er
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakePickedFile(private val data: ByteArray) : PickedFile {
    override val name: String = "test.bin"
    override val size: Long = data.size.toLong()
    override val mimeType: String? = null

    override suspend fun readRange(offset: Long, length: Int): ByteArray? {
        if (offset >= size) return null
        val end = minOf(offset + length, size).toInt()
        return data.copyOfRange(offset.toInt(), end)
    }
}

/**
 * In-memory UploadApi fake. Verifies chunk SHA-256 values against the chunk
 * bytes, records which chunk indexes actually arrived, and supports scripted
 * per-chunk failures (failChunk(index, times) fails that many attempts).
 */
private class FakeApi(private val data: ByteArray, private val chunkSize: Long = 8) : UploadApi {
    var instantUpload: Boolean = false
    var omitFilePayload: Boolean = false
    var receivedChunks: List<Int> = emptyList()
    val sentChunks = mutableMapOf<Int, ByteArray>()
    val chunkAttempts = mutableMapOf<Int, Int>()
    var initCalls = 0
    var lastInitRequest: com.linan.barezen_drive.core.dto.UploadInitRequest? = null
    var completed = false
    var abortCalls = 0
    var shaMismatch = false
    val thumbs = mutableListOf<Pair<String, ByteArray>>()
    var failThumbs = false
    /**
     * Parks every chunk upload until this completes. Two concurrent uploads on
     * one manager then sit in putChunk at the same time, which is the only
     * window where a shared "active session" slot can be caught cross-wired.
     */
    var chunkGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    /** Server sessions this fake was asked to abort, in call order. */
    val abortedIds = mutableListOf<String>()
    private val failuresLeft = mutableMapOf<Int, Int>()

    fun failChunk(index: Int, times: Int) {
        failuresLeft[index] = times
    }

    private fun file(): FileDto = FileDto(
        id = "f1", name = "test.bin", folderId = null, size = data.size.toLong(),
        mimeType = null, sha256 = "0".repeat(64), createdAt = "t", updatedAt = "t",
    )

    override suspend fun init(req: com.linan.barezen_drive.core.dto.UploadInitRequest): Result<com.linan.barezen_drive.core.dto.UploadInitResponse> {
        initCalls++
        lastInitRequest = req
        return Result.success(
            com.linan.barezen_drive.core.dto.UploadInitResponse(
                // A fresh session per init, so two concurrent uploads on one
                // manager are distinguishable server-side (and an abort can be
                // attributed to the upload that asked for it).
                uploadId = "u$initCalls",
                chunkSize = chunkSize,
                receivedChunks = receivedChunks,
                instantUpload = instantUpload,
                file = if (instantUpload && !omitFilePayload) file() else null,
            )
        )
    }

    override suspend fun putChunk(id: String, index: Int, bytes: ByteArray, sha: String?): Result<Unit> {
        chunkGate?.await()
        chunkAttempts[index] = (chunkAttempts[index] ?: 0) + 1
        val left = failuresLeft[index] ?: 0
        if (left > 0) {
            failuresLeft[index] = left - 1
            return Result.failure(ApiFailure.Network("flaky chunk $index"))
        }
        val expected = Sha256er.newInstance().apply { update(bytes) }.digestHex()
        if (sha != expected) shaMismatch = true
        sentChunks[index] = bytes
        return Result.success(Unit)
    }

    override suspend fun complete(id: String): Result<FileDto> {
        completed = true
        return Result.success(file())
    }

    override suspend fun abort(id: String): Result<Unit> {
        abortCalls++
        abortedIds.add(id)
        return Result.success(Unit)
    }

    override suspend fun putThumbnail(id: String, bytes: ByteArray): Result<Unit> {
        if (failThumbs) return Result.failure(ApiFailure.Network("thumb upload failed"))
        thumbs.add(id to bytes)
        return Result.success(Unit)
    }
}

/**
 * Collects one call's progress feed. The manager has no shared progress state
 * any more (that slot is what made concurrent uploads overwrite each other), so
 * a test that wants the phases has to own the sink it hands to the call.
 */
private class ProgressLog {
    val phases = mutableListOf<UploadManager.Phase>()
    var last: UploadManager.Progress? = null
        private set

    val sink: (UploadManager.Progress) -> Unit = { p ->
        phases += p.phase
        last = p
    }
}

/** Streams the byte array through Sha256er with odd-sized reads, like the manager does. */
private fun sha256Of(data: ByteArray, readSize: Int = 7): String {
    val hasher = Sha256er.newInstance()
    var off = 0
    while (off < data.size) {
        val end = minOf(off + readSize, data.size)
        hasher.update(data.copyOfRange(off, end))
        off = end
    }
    return hasher.digestHex()
}

class UploadManagerTest {

    @Test
    fun uploadsAllChunksInOrderAndCompletes() = runTest {
        val data = (0 until 30).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        val mgr = UploadManager(api, backoffBaseMs = 10)
        val log = ProgressLog()

        val res = mgr.upload(FakePickedFile(data), null, onProgress = log.sink)

        assertTrue(res.isSuccess, "upload should succeed: $res")
        assertEquals("f1", res.getOrNull()!!.id)
        assertEquals(1, api.initCalls)
        assertTrue(api.completed, "complete should be called")
        assertEquals(listOf(0, 1, 2, 3), api.sentChunks.keys.sorted())
        val sent = api.sentChunks.keys.sorted().flatMap { api.sentChunks.getValue(it).toList() }
        assertEquals(data.toList(), sent)
        assertTrue(!api.shaMismatch, "every chunk sha must match its bytes")
        val initReq = api.lastInitRequest!!
        assertEquals("test.bin", initReq.name)
        assertEquals(30L, initReq.size)
        assertEquals(sha256Of(data), initReq.sha256, "whole-file sha must match content")
        val p = log.last!!
        assertEquals(UploadManager.Phase.DONE, p.phase)
        assertEquals(30L, p.bytesDone)
        assertEquals(30L, p.bytesTotal)
        assertNull(p.error)
    }

    @Test
    fun instantUploadSkipsChunksAndComplete() = runTest {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        api.instantUpload = true
        val mgr = UploadManager(api, backoffBaseMs = 10)
        val log = ProgressLog()

        val res = mgr.upload(FakePickedFile(data), null, onProgress = log.sink)

        assertTrue(res.isSuccess, "instant upload should succeed: $res")
        assertEquals("f1", res.getOrNull()!!.id)
        assertEquals(1, api.initCalls)
        assertTrue(api.sentChunks.isEmpty(), "no chunks should be sent on instant upload")
        assertTrue(!api.completed, "complete should not be called on instant upload")
        assertEquals(UploadManager.Phase.DONE, log.phases.last())
    }

    @Test
    fun resumeSkipsReceivedChunks() = runTest {
        val data = (0 until 30).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        api.receivedChunks = listOf(1)
        val mgr = UploadManager(api, backoffBaseMs = 10)
        val log = ProgressLog()

        val res = mgr.upload(FakePickedFile(data), null, onProgress = log.sink)

        assertTrue(res.isSuccess, "resumed upload should succeed: $res")
        assertEquals(setOf(0, 2, 3), api.sentChunks.keys)
        assertTrue(1 !in api.chunkAttempts, "received chunk 1 must not be re-sent")
        assertTrue(api.completed)
        // Reassembled sent chunks must equal the file with chunk 1's range removed.
        val expected = data.toMutableList().apply { subList(8, 16).clear() }
        val sent = api.sentChunks.keys.sorted().flatMap { api.sentChunks.getValue(it).toList() }
        assertEquals(expected, sent)
        assertEquals(UploadManager.Phase.DONE, log.phases.last())
    }

    @Test
    fun retryFailedReUploadsTheSameSourceAndSettlesTheRow() = runTest {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        api.failChunk(index = 1, times = 9) // every attempt of chunk 1 fails
        val mgr = UploadManager(api, backoffBaseMs = 10)

        // Claim the row THIS upload created. TransferCenter is a process-wide
        // singleton and push() prepends (newest first), so "the last FAILED row"
        // is the OLDEST failed row - any earlier test that left one behind (a
        // download row, another upload) would be picked up here, and a download
        // row has no retry handle. Diffing against a snapshot says what the test
        // means instead of depending on what ran before it.
        val before = com.linan.barezen_drive.data.transfer.TransferCenter.items.value
            .mapTo(HashSet()) { it.id }
        val first = mgr.upload(FakePickedFile(data), null)
        assertTrue(first.isFailure, "first upload should fail: $first")
        val failedId = com.linan.barezen_drive.data.transfer.TransferCenter.items.value
            .first { it.id !in before && it.phase == com.linan.barezen_drive.data.transfer.TransferPhase.FAILED }
            .id
        assertTrue(
            com.linan.barezen_drive.data.transfer.TransferCenter.retryHandle(failedId) != null,
            "a failed single-file upload must keep a retry handle",
        )

        // The flaky chunk recovers; the retry re-runs the very same source.
        api.failChunk(index = 1, times = 0)
        val retried = mgr.retryFailed(failedId)

        assertTrue(retried?.isSuccess == true, "retry should succeed: $retried")
        assertTrue(api.completed)
        val row = com.linan.barezen_drive.data.transfer.TransferCenter.items.value
            .first { it.id == failedId }
        assertEquals(com.linan.barezen_drive.data.transfer.TransferPhase.DONE, row.phase)
        assertNull(row.error, "a settled retry must not keep the old error text")
    }

    @Test
    fun retryFailedReturnsNullForUnknownRow() = runTest {
        val mgr = UploadManager(FakeApi(ByteArray(4), chunkSize = 8), backoffBaseMs = 10)
        assertNull(mgr.retryFailed("t-does-not-exist"), "unknown rows are not retryable")
    }

    /**
     * A single "currently active session" slot is not only a progress-display
     * bug, it corrupts cleanup: two uploads sharing one manager overwrite the
     * slot, so cancelling the FIRST one aborts the SECOND upload's server
     * session (and the first one leaks its own). Each call must own the session
     * it opened.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun cancellingOneConcurrentUploadAbortsOnlyItsOwnSession() = runTest {
        val dataA = ByteArray(16) { 1 }
        val dataB = ByteArray(16) { 2 }
        val api = FakeApi(dataA, chunkSize = 8)
        val gate = CompletableDeferred<Unit>()
        api.chunkGate = gate
        // coverGen off: the default one hops to Dispatchers.IO, which the test
        // scheduler cannot drain, and the assertion is about the session, not
        // about the cover.
        val mgr = UploadManager(api, backoffBaseMs = 10, coverGen = { null })

        val jobA = launch { mgr.upload(FakePickedFile(dataA), null) }
        val jobB = launch { mgr.upload(FakePickedFile(dataB), null) }
        // Both are parked inside putChunk now, each holding a live session.
        advanceUntilIdle()

        jobA.cancel()
        advanceUntilIdle()

        assertEquals(
            listOf("u1"),
            api.abortedIds,
            "cancelling upload A must abort A's own session, never the one the concurrent upload opened",
        )

        // The survivor must still finish: the abort above was not its session.
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(jobB.isCompleted && !jobB.isCancelled, "the untouched upload must run to the end")
        assertTrue(api.completed, "the untouched upload must still reach complete")
    }

    /**
     * The files page renders TransferCenter's rows, so concurrent uploads have
     * to stay two rows - not one shared slot that only the last writer owns.
     */
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun concurrentUploadsGetTheirOwnTransferRow() = runTest {
        val dataA = ByteArray(16) { 1 }
        val dataB = ByteArray(16) { 2 }
        val api = FakeApi(dataA, chunkSize = 8)
        val gate = CompletableDeferred<Unit>()
        api.chunkGate = gate
        val mgr = UploadManager(api, backoffBaseMs = 10)
        val before = com.linan.barezen_drive.data.transfer.TransferCenter.items.value
            .mapTo(HashSet()) { it.id }

        val jobA = async { mgr.upload(FakePickedFile(dataA), null) }
        val jobB = async { mgr.upload(FakePickedFile(dataB), null) }
        advanceUntilIdle()

        val live = com.linan.barezen_drive.data.transfer.TransferCenter.items.value
            .filter { it.id !in before }
        assertEquals(2, live.size, "each upload must own a transfer row, not share one")
        assertEquals(
            2,
            live.map { it.retry != null }.count { it },
            "a live single-file row must keep its source for a later retry",
        )

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(jobA.await().isSuccess)
        assertTrue(jobB.await().isSuccess)
    }

    @Test
    fun retriesFlakyChunkUpToThreeAttempts() = runTest {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        api.failChunk(index = 1, times = 2) // fails twice, third attempt succeeds
        val mgr = UploadManager(api, backoffBaseMs = 10)
        val log = ProgressLog()

        val res = mgr.upload(FakePickedFile(data), null, onProgress = log.sink)

        assertTrue(res.isSuccess, "upload should succeed after retries: $res")
        assertEquals(3, api.chunkAttempts[1], "chunk 1 must have exactly 3 attempts")
        assertTrue(api.completed)
        assertEquals(0, api.abortCalls)
        assertEquals(UploadManager.Phase.DONE, log.phases.last())
    }

    @Test
    fun givesUpAfterThreeFailedAttemptsAndAborts() = runTest {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        api.failChunk(index = 1, times = 3) // every attempt fails
        val mgr = UploadManager(api, backoffBaseMs = 10)
        val log = ProgressLog()

        val res = mgr.upload(FakePickedFile(data), null, onProgress = log.sink)

        assertTrue(res.isFailure, "upload must fail after 3 failed attempts")
        assertTrue(res.exceptionOrNull() is ApiFailure.Network, "failure must surface the ApiFailure")
        assertEquals(3, api.chunkAttempts[1], "at most 3 attempts per chunk")
        assertTrue(!api.completed, "complete must not be called")
        assertEquals(1, api.abortCalls, "abandoned session must be aborted")
        val p = log.last!!
        assertEquals(UploadManager.Phase.FAILED, p.phase)
        assertTrue(p.error is ApiFailure.Network)
    }

    @Test
    fun instantUploadWithoutFilePayloadFailsGracefully() = runTest {
        val data = "x".encodeToByteArray()
        val api = FakeApi(data, chunkSize = 8)
        api.instantUpload = true
        api.omitFilePayload = true
        val mgr = UploadManager(api, backoffBaseMs = 10)
        val log = ProgressLog()

        val res = mgr.upload(FakePickedFile(data), null, onProgress = log.sink)

        // Malformed server response must surface as Result.failure (not an escaped NPE)
        assertTrue(res.isFailure, "missing file payload must fail the result: $res")
        assertEquals(UploadManager.Phase.FAILED, log.phases.last())
    }

    @Test
    fun coverUploadedAfterComplete() = runTest {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        val cover = byteArrayOf(1, 2, 3)
        val mgr = UploadManager(api, backoffBaseMs = 10, coverGen = { cover })
        val log = ProgressLog()

        val res = mgr.upload(FakePickedFile(data), null, onProgress = log.sink)

        assertTrue(res.isSuccess, "upload should succeed: $res")
        assertEquals(listOf("f1" to cover), api.thumbs, "cover must be PUT after complete")
        assertEquals(UploadManager.Phase.DONE, log.phases.last())
    }

    @Test
    fun coverFailureNeverFailsUpload() = runTest {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        api.failThumbs = true
        val throwing = UploadManager(api, backoffBaseMs = 10, coverGen = { error("boom") })
        val logThrowing = ProgressLog()
        val resThrowing = throwing.upload(FakePickedFile(data), null, onProgress = logThrowing.sink)
        assertTrue(resThrowing.isSuccess, "generator exception must not fail the upload")
        assertEquals(UploadManager.Phase.DONE, logThrowing.phases.last())

        val failingApi = FakeApi(data, chunkSize = 8)
        val mgrFailingApi = UploadManager(failingApi, backoffBaseMs = 10, coverGen = { byteArrayOf(9) })
        val logApi = ProgressLog()
        val res = mgrFailingApi.upload(FakePickedFile(data), null, onProgress = logApi.sink)
        assertTrue(res.isSuccess, "thumbnail API failure must not fail the upload")
        assertEquals(UploadManager.Phase.DONE, logApi.phases.last())
    }

    /**
     * The cover chain swallowed both halves with a bare getOrNull(). A local
     * decoder failure and a rejected PUT need completely different fixes, so
     * the two stages must be distinguishable in the log - and a stage that
     * worked must never be reported as the one that failed.
     */
    @Test
    fun coverGenerationFailureIsLogged() = runTest {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val lines = mutableListOf<String>()
        val mgr = UploadManager(
            api = FakeApi(data, chunkSize = 8),
            backoffBaseMs = 10,
            coverGen = { error("no decoder for this format") },
            log = { lines.add(it) },
        )

        val res = mgr.upload(FakePickedFile(data), null)

        assertTrue(res.isSuccess, "a cover failure must not fail the upload")
        assertTrue(
            lines.any { it.contains("cover gen") && it.contains("test.bin") },
            "local cover generation failure must be logged with the file name: $lines",
        )
        assertTrue(
            lines.none { it.contains("cover put") },
            "nothing was PUT, so the put stage must stay silent: $lines",
        )
    }

    @Test
    fun coverUploadFailureIsLoggedSeparately() = runTest {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        api.failThumbs = true
        val lines = mutableListOf<String>()
        val mgr = UploadManager(
            api = api,
            backoffBaseMs = 10,
            coverGen = { byteArrayOf(1) },
            log = { lines.add(it) },
        )

        val res = mgr.upload(FakePickedFile(data), null)

        assertTrue(res.isSuccess, "a rejected cover PUT must not fail the upload")
        assertTrue(
            lines.any { it.contains("cover put") && it.contains("f1") },
            "a rejected cover PUT must be logged against the file it belongs to: $lines",
        )
        assertTrue(
            lines.none { it.contains("cover gen") },
            "generation succeeded, so it must not be logged as the failure: $lines",
        )
    }

    @Test
    fun instantUploadSkipsCover() = runTest {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        api.instantUpload = true
        val mgr = UploadManager(api, backoffBaseMs = 10, coverGen = { byteArrayOf(1) })

        val res = mgr.upload(FakePickedFile(data), null)

        assertTrue(res.isSuccess)
        assertTrue(api.thumbs.isEmpty(), "instant upload must not re-upload an existing cover")
        assertTrue(!api.completed)
    }

    @Test
    fun nullCoverSkipsThumbnailCall() = runTest {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        val mgr = UploadManager(api, backoffBaseMs = 10, coverGen = { null })
        val log = ProgressLog()

        val res = mgr.upload(FakePickedFile(data), null, onProgress = log.sink)

        assertTrue(res.isSuccess)
        assertTrue(api.thumbs.isEmpty())
        assertEquals(UploadManager.Phase.DONE, log.phases.last())
    }
}
