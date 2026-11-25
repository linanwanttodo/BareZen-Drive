package com.linan.barezen_drive.data.evidence

import com.linan.barezen_drive.platform.evidenceNowMs
import com.linan.barezen_drive.platform.FileSaveRequest
import kotlinx.coroutines.flow.StateFlow

enum class EvidenceErrorKind {
    PASSWORD_LENGTH, PASSWORD_WRONG, PASSWORD_MISMATCH, COOLDOWN, VAULT_NOT_FOUND, MANIFEST_INVALID,
    PERMISSION_REQUIRED, NOTIFICATION_PERMISSION_REQUIRED, SERVICE_START_DENIED,
    CAMERA_UNAVAILABLE, MICROPHONE_UNAVAILABLE, LOW_STORAGE, UPLOAD_FAILED,
    NETWORK_UNAVAILABLE, DECRYPT_FAILED, UNKNOWN,
}

class EvidenceException(val kind: EvidenceErrorKind, cause: Throwable? = null) :
    RuntimeException(kind.name, cause)

data class EvidencePlaybackRequest(
    val token: String,
    val path: String,
    val media: EvidenceMedia,
)

data class AutoLockPolicy(
    private val lockAfterMs: Long = 30_000,
    private val nowMs: () -> Long = ::evidenceNowMs,
) {
    private var backgroundAtMs: Long? = null
    fun onBackground() { if (backgroundAtMs == null) backgroundAtMs = nowMs() }
    fun onForeground() { backgroundAtMs = null }
    fun shouldLock(atMs: Long = nowMs()): Boolean =
        backgroundAtMs?.let { atMs - it >= lockAfterMs } == true
}

interface EvidenceController {
    val recording: StateFlow<EvidenceRecordingState>
    val storage: StateFlow<List<EvidenceStorageSummary>>
    val canRecord: Boolean
    suspend fun status(): EvidenceVaultStatus
    suspend fun createVault(password: String)
    suspend fun unlock(password: String)
    suspend fun cooldownSeconds(): Long
    fun lock()
    suspend fun start(mode: EvidenceMode, lens: EvidenceLens)
    suspend fun stop()
    suspend fun sessions(): List<EvidenceSessionView>
    suspend fun cloudVaults(): List<EvidenceCloudVault>
    suspend fun restore(vaultId: String, password: String)
    suspend fun changePassword(oldPassword: String, newPassword: String)
    suspend fun deleteVault()
    suspend fun playback(segmentKey: String): EvidencePlaybackRequest
    fun closePlayback(token: String)
    suspend fun exportRequest(segmentKey: String): FileSaveRequest?
    suspend fun deleteLocalSegments(segmentKeys: List<String>)
}
