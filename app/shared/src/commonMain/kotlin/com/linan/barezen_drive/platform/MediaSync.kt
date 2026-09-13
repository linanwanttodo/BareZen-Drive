package com.linan.barezen_drive.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Background album sync (upload new device media automatically).
 *
 * Only Android has a background scheduler; other targets report unsupported
 * and the transfer-centre switch is hidden. The switch state and the network /
 * charging policy are passed in by the UI, which persists them in preferences.
 */
expect object MediaSync {
    val supported: Boolean

    /** Live backup progress for the transfer-centre status card. */
    val status: StateFlow<BackupStatus>

    /**
     * Schedules or cancels the periodic job according to the stored policy:
     * [enabled], Wi-Fi-only ([wifiOnly]) and "only while charging"
     * ([chargingOnly]). A low-battery floor is always applied on top, so a
     * backup can never be the reason a phone dies on the user.
     */
    fun apply(enabled: Boolean, wifiOnly: Boolean, chargingOnly: Boolean)

    /** Runs one immediate pass (the "sync now" action). */
    fun syncNow(wifiOnly: Boolean)

    /** Device albums with per-album queue totals, for the backup settings. */
    suspend fun listBuckets(): List<BackupBucket>

    /**
     * Include or exclude one album from automatic backup (feature 6). Including
     * an album re-drives the queue, because its rows are still sitting there as
     * PENDING and nothing else would pick them up until the next trigger.
     */
    fun setBucketIncluded(bucket: String, included: Boolean)
}

/** Shared empty status used by every non-Android actual. */
internal fun unsupportedBackupStatus(): StateFlow<BackupStatus> = MutableStateFlow(BackupStatus())
