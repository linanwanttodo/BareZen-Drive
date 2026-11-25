package com.linan.barezen_drive.evidence

import com.linan.barezen_drive.data.evidence.EvidenceMedia
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvidenceSegmentCipherTest {
    private val dir = createTempDirectory("evidence-cipher").toFile()
    private val dek = ByteArray(32) { 9 }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
        dek.fill(0)
    }

    @Test
    fun multiChunkRoundTripAndAuthenticatedRangeRead() {
        val plain = File(dir, "plain.mp4").apply {
            writeBytes(ByteArray(SEGMENT_CHUNK_BYTES + 123) { (it % 251).toByte() })
        }
        val encrypted = File(dir, "plain.mp4.enc")
        val header = header().copy(plaintextLength = plain.length(), chunkCount = 2)
        val result = encryptSegment(plain, encrypted, dek, header)
        assertEquals(2, readSegmentHeader(encrypted).header.chunkCount)
        val cache = File(dir, "cache.mp4")
        decryptSegmentToCache(encrypted, cache, dek)
        assertContentEquals(plain.readBytes(), cache.readBytes())
        assertContentEquals(
            plain.readBytes().copyOfRange(SEGMENT_CHUNK_BYTES - 6, SEGMENT_CHUNK_BYTES + 14),
            readAuthenticatedRange(encrypted, dek, SEGMENT_CHUNK_BYTES - 6L, 20),
        )
        assertFalse(File(cache.path + ".part").exists())
        assertEquals(result.ciphertextSha256, encrypted.sha256Hex())
        assertEquals(result.plaintextSha256, verifySegment(encrypted, dek).plaintextSha256)
    }

    @Test
    fun headerNonceTagTruncationAndTrailingBytesAreRejected() {
        val plain = File(dir, "plain.m4a").apply { writeBytes(ByteArray(2_000) { 5 }) }
        val encrypted = File(dir, "plain.m4a.enc")
        val header = header().copy(plaintextLength = plain.length(), chunkCount = 1, media = EvidenceMedia.AUDIO)
        encryptSegment(plain, encrypted, dek, header)
        val bytes = encrypted.readBytes()
        val headerBytes = readSegmentHeader(encrypted).encodedHeader
        val cases = listOf(
            bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
            bytes.copyOf().also { it[84] = (it[84].toInt() xor 1).toByte() },
            bytes.copyOf().also { it[headerBytes.size] = (it[headerBytes.size].toInt() xor 1).toByte() },
            bytes.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() },
            bytes.dropLast(1).toByteArray(),
            bytes + byteArrayOf(0),
        )
        cases.forEachIndexed { index, changed ->
            val file = File(dir, "bad-$index.enc").apply { writeBytes(changed) }
            assertFailsWith<SecurityException> {
                decryptSegmentToCache(file, File(dir, "bad-$index.cache"), dek)
            }
            assertFalse(File(dir, "bad-$index.cache").exists())
            assertFalse(File(dir, "bad-$index.cache.part").exists())
        }
    }

    @Test
    fun emptyAndOversizedSegmentsAreRejectedBeforeWriting() {
        val empty = File(dir, "empty").apply { writeBytes(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> {
            encryptSegment(empty, File(dir, "empty.enc"), dek, header())
        }
        assertFailsWith<IllegalArgumentException> {
            segmentChunkCount((MAX_SEGMENT_CHUNKS + 1) * SEGMENT_CHUNK_BYTES)
        }
        assertTrue(!File(dir, "empty.enc").exists())
    }

    private fun header() = SegmentHeader(
        vaultId = "c".repeat(32),
        sessionId = "20261125_010203_1234abcd",
        segmentNumber = 1,
        media = EvidenceMedia.VIDEO,
        plaintextLength = 0,
        chunkSize = SEGMENT_CHUNK_BYTES,
        chunkCount = 0,
        startedAtMs = 1_700_000_000_000,
        durationMs = 60_000,
        dekVersion = 1,
    )
}
