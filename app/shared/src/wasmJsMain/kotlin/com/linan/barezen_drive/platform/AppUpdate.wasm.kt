package com.linan.barezen_drive.platform

import kotlinx.browser.window

actual val installChannel: InstallChannel = InstallChannel.WEB

actual fun openInBrowser(url: String) {
    window.open(url, "_blank")
}

/**
 * The server serves this bundle from the same origin, so a full reload is the
 * web client's update mechanism: after the server is upgraded, a stale tab
 * re-fetches index.html and the new assets.
 */
actual fun reloadApp() {
    window.location.reload()
}
