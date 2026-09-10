package com.linan.barezen_drive.platform

import kotlinx.browser.window

actual val isAndroidPlatform: Boolean = false

// The web client serves itself from the same origin it runs on, so a store
// style update check does not apply; hard refresh picks up new builds.
actual suspend fun latestReleaseTag(): String? = null

actual fun openInBrowser(url: String) {
    window.open(url, "_blank")
}
