package com.linan.barezen_drive.evidence

import com.linan.barezen_drive.data.evidence.EvidenceMedia
import com.linan.barezen_drive.data.evidence.SegmentKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalEncodingApi::class)
class EvidenceCoreTest {
    @Test
    fun segmentKeyRoundTripsWithoutLocaleFormatting() {
        val key = SegmentKey("a".repeat(32), "20261125_010203_1234abcd", 7)
        assertEquals("${key.vaultId}/${key.sessionId}/007", key.toString())
        assertEquals(key, SegmentKey.parse(key.toString()))
        assertFailsWith<IllegalArgumentException> { SegmentKey.parse("a/b/0") }
    }

    @Test
    fun segmentHeaderRoundTripsAndBindsSessionAndChunk() {
        val header = SegmentHeader(
            vaultId = "c".repeat(32),
            sessionId = "20261125_010203_1234abcd",
            segmentNumber = 2,
            media = EvidenceMedia.VIDEO,
            plaintextLength = 1_048_700,
            chunkSize = 1_048_576,
            chunkCount = 2,
            startedAtMs = 1_700_000_000_000,
            durationMs = 60_000,
            dekVersion = 1,
        )
        val bytes = encodeSegmentHeader(header)
        assertEquals(header, decodeSegmentHeader(bytes))
        assertEquals(84 + header.sessionId.encodeToByteArray().size, bytes.size)
        assertContentEquals(
            bytes + byteArrayOf(0, 0, 0, 1),
            segmentAad(bytes, 1),
        )
        assertFailsWith<RuntimeException> {
            decodeSegmentHeader(bytes.copyOf().also { it[0] = 'X'.code.toByte() })
        }
    }

    @Test
    fun chunkCountRejectsEmptyAndMoreThanU32Chunks() {
        assertEquals(1, segmentChunkCount(1))
        assertEquals(2, segmentChunkCount(1_048_577))
        assertFailsWith<IllegalArgumentException> { segmentChunkCount(0) }
        assertFailsWith<IllegalArgumentException> {
            segmentChunkCount(4_294_967_296L * 1_048_576L)
        }
    }

    @Test
    fun manifestRejectsNonCanonicalJsonAndUnknownFields() {
        val json = EvidenceJson
        val manifest = VaultManifest(
            version = 1,
            vaultId = "d".repeat(32),
            kdf = "PBKDF2WithHmacSHA256",
            iterations = 600_000,
            salt = Base64.encode(ByteArray(32)),
            wrappedDek = Base64.encode(ByteArray(60)),
            passwordVerifier = Base64.encode(ByteArray(60)),
            dekVersion = 1,
            createdAtMs = 1,
            updatedAtMs = 1,
            tag = "00".repeat(32),
        )
        val canonical = json.encodeToString(manifest).encodeToByteArray()
        assertEquals(manifest, parseVaultManifest(canonical))
        assertFailsWith<IllegalArgumentException> {
            parseVaultManifest((" " + canonical.decodeToString()).encodeToByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            parseVaultManifest(canonical.decodeToString().dropLast(1).plus(",\"extra\":1}").encodeToByteArray())
        }
    }
}
