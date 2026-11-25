package com.linan.barezen_drive.evidence

import com.linan.barezen_drive.data.evidence.EvidenceMedia
import com.linan.barezen_drive.data.evidence.EvidenceMode
import com.linan.barezen_drive.data.evidence.EvidenceSegmentState
import com.linan.barezen_drive.data.evidence.SegmentKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object EvidenceSegmentFinalizer {
    suspend fun finalize(
        plainFile: File,
        vaultId: String,
        sessionId: String,
        segmentNumber: Int,
        mode: EvidenceMode,
        startedAtMs: Long,
        endedAtMs: Long,
        dek: ByteArray,
        serverKey: String,
        accountId: String,
        dekVersion: Int,
    ): EncryptedSegment? = withContext(Dispatchers.IO) {
        if (!plainFile.isFile || plainFile.length() == 0L) {
            plainFile.delete()
            return@withContext null
        }
        val sessionDir = plainFile.parentFile!!
        val cipher = EvidenceFiles.cipherFile(sessionDir, segmentNumber, mode)
        val header = SegmentHeader(
            vaultId = vaultId,
            sessionId = sessionId,
            segmentNumber = segmentNumber,
            media = if (mode == EvidenceMode.VIDEO) EvidenceMedia.VIDEO else EvidenceMedia.AUDIO,
            plaintextLength = plainFile.length(),
            chunkSize = SEGMENT_CHUNK_BYTES,
            chunkCount = segmentChunkCount(plainFile.length()),
            startedAtMs = startedAtMs,
            durationMs = (endedAtMs - startedAtMs).coerceAtLeast(0),
            dekVersion = dekVersion,
        )
        val key = SegmentKey(vaultId, sessionId, segmentNumber)
        EvidenceDb.insertEncryptingSegment(
            SegmentRow(
                vaultId, sessionId, segmentNumber, serverKey, accountId, mode, cipher.name,
                plainFile.absolutePath, cipher.absolutePath, plainFile.length(), null, null,
                startedAtMs, endedAtMs, header.durationMs, 1, dekVersion, EvidenceSegmentState.ENCRYPTING,
                0, 0, null, null, startedAtMs, startedAtMs,
            ),
        )
        try {
            val encrypted = encryptSegment(plainFile, cipher, dek, header)
            val verified = verifySegment(cipher, dek)
            check(verified.header == header)
            check(verified.plaintextSha256 == encrypted.plaintextSha256)
            check(cipher.sha256Hex() == encrypted.ciphertextSha256)
            EvidenceDb.markSegmentPending(key, cipher.length(), encrypted.plaintextSha256, encrypted.ciphertextSha256, endedAtMs)
            plainFile.delete()
            encrypted
        } catch (cancelled: CancellationException) {
            EvidenceFiles.cipherTemp(sessionDir, segmentNumber, mode).delete()
            cipher.delete()
            EvidenceDb.markSegmentInterrupted(key, "cancelled", System.currentTimeMillis())
            throw cancelled
        } catch (t: Throwable) {
            EvidenceFiles.cipherTemp(sessionDir, segmentNumber, mode).delete()
            cipher.delete()
            EvidenceDb.markSegmentInterrupted(key, t::class.simpleName ?: "encryption failed", System.currentTimeMillis())
            null
        }
    }
}
