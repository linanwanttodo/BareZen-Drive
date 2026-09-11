package com.linan.barezen_drive.platform

/**
 * Background album sync (upload new device media automatically).
 *
 * Only Android has a background scheduler; other targets report unsupported
 * and the transfer-centre switch is hidden. The switch state and the network
 * policy are passed in by the UI, which persists them in preferences.
 */
expect object MediaSync {
    val supported: Boolean

    /** Schedules or cancels the periodic job according to [enabled]/[wifiOnly]. */
    fun apply(enabled: Boolean, wifiOnly: Boolean)

    /** Runs one immediate pass (the "sync now" action). */
    fun syncNow(wifiOnly: Boolean)
}
