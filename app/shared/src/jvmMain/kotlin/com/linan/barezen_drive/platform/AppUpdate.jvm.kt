package com.linan.barezen_drive.platform

import java.awt.Desktop
import java.net.URI

/**
 * Reserved actual for the desktop (JVM) target. The target is not enabled in
 * settings.gradle.kts yet; this file keeps the platform contract satisfied so
 * re-enabling :app:desktopApp only needs the module included again.
 */
actual val installChannel: InstallChannel = InstallChannel.DESKTOP

actual fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

/** Packaged desktop app: the user installs the new build from the release page. */
actual fun reloadApp() = Unit
