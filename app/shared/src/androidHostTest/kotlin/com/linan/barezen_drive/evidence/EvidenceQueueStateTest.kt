package com.linan.barezen_drive.evidence

import com.linan.barezen_drive.data.evidence.EvidenceMode
import com.linan.barezen_drive.data.evidence.EvidenceSegmentState
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EvidenceQueueStateTest {
    @Test
    fun onlyVerifiedCiphertextBecomesPending() {
        val row = segment(EvidenceSegmentState.ENCRYPTING)
        assertEquals(EvidenceSegmentState.PENDING, row.apply(QueueEvent.ENCRYPTED, 100).state)
        assertFailsWith<IllegalStateException> { row.apply(QueueEvent.UPLOAD_STARTED, 100) }
    }

    @Test
    fun uploadFailureBacksOffAndProcessDeathResetsUploading() {
        val row = segment(EvidenceSegmentState.PENDING)
        val uploading = row.apply(QueueEvent.UPLOAD_STARTED, 100)
        val failed = uploading.apply(QueueEvent.UPLOAD_FAILED, 1_000)
        assertEquals(EvidenceSegmentState.FAILED, failed.state)
        assertEquals(31_000, failed.nextAttemptAtMs)
        assertEquals(EvidenceSegmentState.PENDING, uploading.apply(QueueEvent.PROCESS_RESTARTED, 9_000).state)
        assertEquals(EvidenceSegmentState.UPLOADED, uploading.apply(QueueEvent.UPLOAD_SUCCEEDED, 2_000).state)
    }

    @Test
    fun interruptedIsTerminal() {
        val interrupted = segment(EvidenceSegmentState.ENCRYPTING).apply(QueueEvent.INTERRUPTED, 10)
        assertEquals(EvidenceSegmentState.INTERRUPTED, interrupted.state)
        assertFailsWith<IllegalStateException> { interrupted.apply(QueueEvent.ENCRYPTED, 20) }
    }

    private fun segment(state: EvidenceSegmentState) = SegmentRow(
        vaultId = "a".repeat(32), sessionId = "20261125_010203_1234abcd", segmentNumber = 1,
        serverKey = "server", accountId = "account", mode = EvidenceMode.AUDIO,
        name = "001_audio.m4a.enc", plainPath = "/plain", cipherPath = "/cipher", size = 1,
        plainSha256 = null, cipherSha256 = null, startedAtMs = 0, endedAtMs = 0, durationMs = 0,
        formatVersion = 1, dekVersion = 1, state = state, attempts = 0, nextAttemptAtMs = 0,
        lastError = null, cloudFileId = null, createdAtMs = 0, updatedAtMs = 0,
    )
}

class EvidenceNamesTest {
    @Test
    fun segmentNamesAndIdsAreStableAndUnique() {
        assertEquals("001_video.mp4.enc", EvidenceNames.segmentName(1, EvidenceMode.VIDEO))
        assertEquals("001_audio.m4a.enc", EvidenceNames.segmentName(1, EvidenceMode.AUDIO))
        assertTrue(EvidenceNames.isVaultId("0123456789abcdef0123456789abcdef"))
        assertFalse(EvidenceNames.isVaultId("A".repeat(32)))
        assertEquals(32, EvidenceNames.vaultId().length)
        assertNotEquals(EvidenceNames.sessionId(1_700_000_000_000), EvidenceNames.sessionId(1_700_000_000_000))
    }

    @Test
    fun pathsRejectTraversal() {
        val root = createTempDirectory("evidence-files").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                EvidenceFiles.sessionDir(root, "a".repeat(32), "../escape")
            }
            assertFailsWith<IllegalArgumentException> {
                EvidenceFiles.sessionDir(root, "A".repeat(32), "20261125_010203_1234abcd")
            }
            val session = EvidenceFiles.sessionDir(root, "a".repeat(32), "20261125_010203_1234abcd")
            assertTrue(session.path.startsWith(root.path + File.separator))
        } finally {
            root.deleteRecursively()
        }
    }
}
