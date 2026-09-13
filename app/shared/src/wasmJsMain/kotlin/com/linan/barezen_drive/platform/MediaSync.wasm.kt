package com.linan.barezen_drive.platform

/**
 * No background scheduler in the browser; the transfer-centre sync switch is
 * hidden (see MediaSync.supported) and the status card never becomes visible.
 */
actual object MediaSync {
    actual val supported: Boolean = false
    actual val status = unsupportedBackupStatus()
    actual fun apply(enabled: Boolean, wifiOnly: Boolean, chargingOnly: Boolean) = Unit
    actual fun syncNow(wifiOnly: Boolean) = Unit
    actual suspend fun listBuckets(): List<BackupBucket> = emptyList()
    actual fun setBucketIncluded(bucket: String, included: Boolean) = Unit
}
