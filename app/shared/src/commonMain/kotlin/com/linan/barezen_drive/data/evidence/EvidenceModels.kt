package com.linan.barezen_drive.data.evidence

enum class EvidenceMode { AUDIO, VIDEO }
enum class EvidenceMedia { AUDIO, VIDEO }
enum class EvidenceLens { BACK, FRONT }

enum class EvidenceSegmentState {
    ENCRYPTING,
    PENDING,
    UPLOADING,
    UPLOADED,
    FAILED,
    INTERRUPTED,
}

enum class EvidenceVaultStatus { MISSING, LOCKED, UNLOCKED }

data class SegmentKey(
    val vaultId: String,
    val sessionId: String,
    val segmentNumber: Int,
) {
    override fun toString(): String = "$vaultId/$sessionId/${segmentNumber.toString().padStart(3, '0')}"

    companion object {
        fun parse(value: String): SegmentKey {
            val parts = value.split('/')
            require(parts.size == 3 && parts[0].matches(VAULT_ID)) { "invalid segment key" }
            require(parts[1].matches(SESSION_ID)) { "invalid segment key" }
            val segment = parts[2].toIntOrNull() ?: throw IllegalArgumentException("invalid segment key")
            require(segment in 1..999) { "invalid segment key" }
            return SegmentKey(parts[0], parts[1], segment)
        }
    }
}

val VAULT_ID = Regex("[0-9a-f]{32}")
val SESSION_ID = Regex("[0-9]{8}_[0-9]{6}_[0-9a-f]{8}")

data class EvidenceSessionView(
    val sessionId: String,
    val mode: EvidenceMode,
    val startedAtMs: Long,
    val segmentCount: Int,
    val uploadedCount: Int,
    val failedCount: Int,
    val localBytes: Long,
    val endedAtMs: Long? = null,
    val state: String = "INTERRUPTED",
    val segments: List<EvidenceSegmentView> = emptyList(),
)

data class EvidenceSegmentView(
    val key: SegmentKey,
    val media: EvidenceMedia,
    val state: EvidenceSegmentState,
    val startedAtMs: Long,
    val durationMs: Long,
    val cipherBytes: Long,
    val localFileId: String?,
    val cloudFileId: String?,
    val error: String?,
)

data class EvidenceStorageSummary(
    val sessionId: String,
    val segmentCount: Int,
    val localBytes: Long,
)

data class EvidenceCloudVault(
    val vaultId: String,
    val manifestFileId: String,
    val updatedAtMs: Long?,
)

data class EvidenceRecordingState(
    val mode: EvidenceMode? = null,
    val sessionId: String? = null,
    val startedAtMs: Long = 0,
    val elapsedMs: Long = 0,
    val sealedSegments: Int = 0,
    val stopping: Boolean = false,
    val uploadLabel: String? = null,
    val error: EvidenceErrorKind? = null,
) {
    val active: Boolean get() = mode != null
}

data class EvidencePlayableMedia(
    val key: SegmentKey,
    val media: EvidenceMedia,
    val token: String,
)
