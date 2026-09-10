package com.linan.barezen_drive.platform

import android.content.Intent
import android.net.Uri
import com.linan.barezen_drive.AndroidContext

actual val installChannel: InstallChannel = InstallChannel.ANDROID

actual fun openInBrowser(url: String) {
    runCatching {
        AndroidContext.app.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Packaged app: the user installs the new APK from the release page, so there
 * is no in-process reload to perform.
 */
actual fun reloadApp() = Unit
