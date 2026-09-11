package com.linan.barezen_drive.platform

/**
 * No background scheduler in the browser; the transfer-centre sync switch is
 * hidden (see MediaSync.supported).
 */
actual object MediaSync {
    actual val supported: Boolean = false
    actual fun apply(enabled: Boolean, wifiOnly: Boolean) = Unit
    actual fun syncNow(wifiOnly: Boolean) = Unit
}
