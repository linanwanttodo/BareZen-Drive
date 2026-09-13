package com.linan.barezen_drive.platform

/**
 * Why the album backup has nothing running even though it is switched on.
 * Ordered by the precedence [backupPauseReason] applies: nothing can upload
 * before sign-in, an unreachable server matters more to the user than a charging
 * preference, and "plug it in" is the instruction that also clears a low battery.
 */
enum class BackupPauseReason {
    /** Auto-sync is on but this client has no session: every pass returns early. */
    SIGNED_OUT,

    /** No usable link: offline, or metered data while Wi-Fi-only is set. */
    NETWORK,

    /** The "only while charging" preference is on and the device is on battery. */
    CHARGING,

    /** Battery saver / low charge: the platform would refuse the job anyway. */
    BATTERY_LOW,
}

/**
 * Pure mapping from scheduler inputs to a pause reason, or null when nothing is
 * deliberately holding the queue back. Kept free of Android types so it is
 * unit-testable - see BackupPauseReasonTest.
 */
fun backupPauseReason(
    signedIn: Boolean,
    hasUsableNetwork: Boolean,
    needsCharging: Boolean,
    isCharging: Boolean,
    needsBatteryNotLow: Boolean,
    isBatteryLow: Boolean,
): BackupPauseReason? = when {
    !signedIn -> BackupPauseReason.SIGNED_OUT
    !hasUsableNetwork -> BackupPauseReason.NETWORK
    needsCharging && !isCharging -> BackupPauseReason.CHARGING
    needsBatteryNotLow && isBatteryLow -> BackupPauseReason.BATTERY_LOW
    else -> null
}

/**
 * Cross-platform snapshot of the automatic album backup feature, surfaced by
 * the transfer centre. Only Android drives this; other targets keep the empty
 * state so the card stays hidden.
 *
 * [pending] is how many device items still await upload (albums the user
 * excluded are not counted), [active] how many are transferring right now,
 * [failed] the errors recorded since the last success. [pausedReason] explains
 * why nothing is running even though [enabled] is on; null means it is running,
 * or idle with nothing to do.
 */
data class BackupStatus(
    val enabled: Boolean = false,
    val pending: Int = 0,
    val active: Int = 0,
    val failed: Int = 0,
    val excludedBuckets: Int = 0,
    val lastSyncAt: Long = 0L,
    val pausedReason: BackupPauseReason? = null,
) {
    /** True when there is any row worth showing in the UI. */
    val visible: Boolean
        get() = enabled
}

/** One device album offered by the per-album backup settings (feature 6). */
data class BackupBucket(
    val name: String,
    val total: Int,
    val included: Boolean,
)
