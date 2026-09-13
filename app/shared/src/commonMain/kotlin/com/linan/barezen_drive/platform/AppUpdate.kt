package com.linan.barezen_drive.platform

/** Which distribution channel this build was installed through. */
enum class InstallChannel { ANDROID, IOS, DESKTOP, WEB }


expect val installChannel: InstallChannel

/** Opens an external URL in the system browser. */
expect fun openInBrowser(url: String)

/**
 * Downloads the package inside the app (progress 0..1 reported to
 * [onProgress]) and hands the finished file to the OS installer. The URLs are
 * mirrors of the same package, tried in order: the first one that yields a
 * complete download wins. Returns true when the hand-off started; false means
 * every source failed and the caller should fall back to the release page.
 */
expect suspend fun downloadAndInstallUpdate(
    urls: List<String>,
    onProgress: (Float) -> Unit = {},
): Boolean

/**
 * Reloads the running app so it picks up a newer build served by the same
 * origin. Meaningful on the web client (the server ships the bundle); on
 * packaged apps the OS restarts the process instead, so this is a no-op.
 */
expect fun reloadApp()
