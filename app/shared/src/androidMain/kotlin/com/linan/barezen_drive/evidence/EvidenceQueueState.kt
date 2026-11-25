package com.linan.barezen_drive.evidence

import com.linan.barezen_drive.data.evidence.EvidenceMode
import com.linan.barezen_drive.data.evidence.EvidenceSegmentState
import com.linan.barezen_drive.data.evidence.SegmentKey
import com.linan.barezen_drive.data.evidence.VAULT_ID
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VaultRow(
    val vaultId: String,
    val serverKey: String,
    val accountId: String,
    val manifest: ByteArray,
    val dekVersion: Int,
    val evidenceRootFolderId: String?,
    val vaultFolderId: String?,
    val manifestFileId: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

data class SessionRow(
    val sessionId: String,
    val vaultId: String,
    val serverKey: String,
    val accountId: String,
    val mode: EvidenceMode,
    val state: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val cloudFolderId: String?,
)

data class SegmentRow(
    val vaultId: String,
    val sessionId: String,
    val segmentNumber: Int,
    val serverKey: String,
    val accountId: String,
    val mode: EvidenceMode,
    val name: String,
    val plainPath: String,
    val cipherPath: String,
    val size: Long,
    val plainSha256: String?,
    val cipherSha256: String?,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationMs: Long,
    val formatVersion: Int,
    val dekVersion: Int,
    val state: EvidenceSegmentState,
    val attempts: Int,
    val nextAttemptAtMs: Long,
    val lastError: String?,
    val cloudFileId: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val localDeleted: Boolean = false,
) {
    val key: SegmentKey get() = SegmentKey(vaultId, sessionId, segmentNumber)
}

enum class QueueEvent { ENCRYPTED, UPLOAD_STARTED, UPLOAD_SUCCEEDED, UPLOAD_FAILED, PROCESS_RESTARTED, INTERRUPTED }

fun SegmentRow.apply(event: QueueEvent, nowMs: Long): SegmentRow = when (event) {
    QueueEvent.ENCRYPTED -> {
        check(state == EvidenceSegmentState.ENCRYPTING)
        copy(state = EvidenceSegmentState.PENDING, updatedAtMs = nowMs, nextAttemptAtMs = 0)
    }
    QueueEvent.UPLOAD_STARTED -> {
        check(state == EvidenceSegmentState.PENDING || state == EvidenceSegmentState.FAILED)
        copy(state = EvidenceSegmentState.UPLOADING, updatedAtMs = nowMs, lastError = null)
    }
    QueueEvent.UPLOAD_SUCCEEDED -> {
        check(state == EvidenceSegmentState.UPLOADING)
        copy(state = EvidenceSegmentState.UPLOADED, updatedAtMs = nowMs, nextAttemptAtMs = 0, lastError = null)
    }
    QueueEvent.UPLOAD_FAILED -> {
        check(state == EvidenceSegmentState.UPLOADING)
        val nextAttempts = attempts + 1
        copy(
            state = EvidenceSegmentState.FAILED,
            attempts = nextAttempts,
            nextAttemptAtMs = nowMs + UploadBackoff.delayMs(nextAttempts),
            lastError = "upload failed",
            updatedAtMs = nowMs,
        )
    }
    QueueEvent.PROCESS_RESTARTED -> {
        check(state == EvidenceSegmentState.UPLOADING)
        copy(state = EvidenceSegmentState.PENDING, updatedAtMs = nowMs)
    }
    QueueEvent.INTERRUPTED -> {
        check(state != EvidenceSegmentState.UPLOADED)
        copy(state = EvidenceSegmentState.INTERRUPTED, updatedAtMs = nowMs, lastError = "interrupted")
    }
}

object UploadBackoff {
    fun delayMs(attempts: Int): Long =
        (30_000L shl (attempts - 1).coerceIn(0, 8)).coerceAtMost(6 * 60 * 60 * 1000L)
}

object EvidenceNames {
    private val random = SecureRandom()
    private val timeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun vaultId(): String = ByteArray(16).also(random::nextBytes).toHex()
    fun sessionId(nowMs: Long): String =
        timeFormat.format(Date(nowMs)) + "_" + ByteArray(4).also(random::nextBytes).toHex()

    fun segmentName(number: Int, mode: EvidenceMode): String {
        check(number in 1..999)
        val suffix = if (mode == EvidenceMode.VIDEO) "video.mp4.enc" else "audio.m4a.enc"
        return "%03d_%s".format(Locale.US, number, suffix)
    }

    fun isVaultId(value: String): Boolean = value.matches(VAULT_ID)
}
