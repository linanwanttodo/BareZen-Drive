package com.linan.barezen_drive

import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.api.ApiFailure
import com.linan.barezen_drive.data.repo.UploadApi
import com.linan.barezen_drive.data.upload.UploadManager
import com.linan.barezen_drive.platform.PickedFile
import com.linan.barezen_drive.platform.Sha256er
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
                uploadId = "u1",
                chunkSize = chunkSize,
                receivedChunks = receivedChunks,
                instantUpload = instantUpload,
                file = if (instantUpload && !omitFilePayload) file() else null,
            )
        )
    }

    override suspend fun putChunk(id: String, index: Int, bytes: ByteArray, sha: String?): Result<Unit> {
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
        return Result.success(Unit)
    }

    override suspend fun putThumbnail(id: String, bytes: ByteArray): Result<Unit> {
        if (failThumbs) return Result.failure(ApiFailure.Network("thumb upload failed"))
        thumbs.add(id to bytes)
        return Result.success(Unit)
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

        val res = mgr.upload(FakePickedFile(data), null)

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
        val p = mgr.progress.value
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

        val res = mgr.upload(FakePickedFile(data), null)

        assertTrue(res.isSuccess, "instant upload should succeed: $res")
        assertEquals("f1", res.getOrNull()!!.id)
        assertEquals(1, api.initCalls)
        assertTrue(api.sentChunks.isEmpty(), "no chunks should be sent on instant upload")
        assertTrue(!api.completed, "complete should not be called on instant upload")
        assertEquals(UploadManager.Phase.DONE, mgr.progress.value.phase)
    }

    @Test
    fun resumeSkipsReceivedChunks() = runTest {
        val data = (0 until 30).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        api.receivedChunks = listOf(1)
        val mgr = UploadManager(api, backoffBaseMs = 10)

        val res = mgr.upload(FakePickedFile(data), null)

        assertTrue(res.isSuccess, "resumed upload should succeed: $res")
        assertEquals(setOf(0, 2, 3), api.sentChunks.keys)
        assertTrue(1 !in api.chunkAttempts, "received chunk 1 must not be re-sent")
        assertTrue(api.completed)
        // Reassembled sent chunks must equal the file with chunk 1's range removed.
        val expected = data.toMutableList().apply { subList(8, 16).clear() }
        val sent = api.sentChunks.keys.sorted().flatMap { api.sentChunks.getValue(it).toList() }
        assertEquals(expected, sent)
        assertEquals(UploadManager.Phase.DONE, mgr.progress.value.phase)
    }

    @Test
    fun retriesFlakyChunkUpToThreeAttempts() = runTest {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        api.failChunk(index = 1, times = 2) // fails twice, third attempt succeeds
        val mgr = UploadManager(api, backoffBaseMs = 10)

        val res = mgr.upload(FakePickedFile(data), null)

        assertTrue(res.isSuccess, "upload should succeed after retries: $res")
        assertEquals(3, api.chunkAttempts[1], "chunk 1 must have exactly 3 attempts")
        assertTrue(api.completed)
        assertEquals(0, api.abortCalls)
        assertEquals(UploadManager.Phase.DONE, mgr.progress.value.phase)
    }

    @Test
    fun givesUpAfterThreeFailedAttemptsAndAborts() = runTest {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        api.failChunk(index = 1, times = 3) // every attempt fails
        val mgr = UploadManager(api, backoffBaseMs = 10)

        val res = mgr.upload(FakePickedFile(data), null)

        assertTrue(res.isFailure, "upload must fail after 3 failed attempts")
        assertTrue(res.exceptionOrNull() is ApiFailure.Network, "failure must surface the ApiFailure")
        assertEquals(3, api.chunkAttempts[1], "at most 3 attempts per chunk")
        assertTrue(!api.completed, "complete must not be called")
        assertEquals(1, api.abortCalls, "abandoned session must be aborted")
        val p = mgr.progress.value
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

        val res = mgr.upload(FakePickedFile(data), null)

        // Malformed server response must surface as Result.failure (not an escaped NPE)
        assertTrue(res.isFailure, "missing file payload must fail the result: $res")
        assertEquals(UploadManager.Phase.FAILED, mgr.progress.value.phase)
    }

    @Test
    fun coverUploadedAfterComplete() = runTest {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        val cover = byteArrayOf(1, 2, 3)
        val mgr = UploadManager(api, backoffBaseMs = 10, coverGen = { cover })

        val res = mgr.upload(FakePickedFile(data), null)

        assertTrue(res.isSuccess, "upload should succeed: $res")
        assertEquals(listOf("f1" to cover), api.thumbs, "cover must be PUT after complete")
        assertEquals(UploadManager.Phase.DONE, mgr.progress.value.phase)
    }

    @Test
    fun coverFailureNeverFailsUpload() = runTest {
        val data = (0 until 16).map { it.toByte() }.toByteArray()
        val api = FakeApi(data, chunkSize = 8)
        api.failThumbs = true
        val throwing = UploadManager(api, backoffBaseMs = 10, coverGen = { error("boom") })
        val resThrowing = throwing.upload(FakePickedFile(data), null)
        assertTrue(resThrowing.isSuccess, "generator exception must not fail the upload")
        assertEquals(UploadManager.Phase.DONE, throwing.progress.value.phase)

        val failingApi = FakeApi(data, chunkSize = 8)
        val mgrFailingApi = UploadManager(failingApi, backoffBaseMs = 10, coverGen = { byteArrayOf(9) })
        val res = mgrFailingApi.upload(FakePickedFile(data), null)
        assertTrue(res.isSuccess, "thumbnail API failure must not fail the upload")
        assertEquals(UploadManager.Phase.DONE, mgrFailingApi.progress.value.phase)
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

        val res = mgr.upload(FakePickedFile(data), null)

        assertTrue(res.isSuccess)
        assertTrue(api.thumbs.isEmpty())
        assertEquals(UploadManager.Phase.DONE, mgr.progress.value.phase)
    }
}
