package com.linan.barezen_drive.platform

/** Which distribution channel this build was installed through. */
enum class InstallChannel { ANDROID, IOS, DESKTOP, WEB }


expect val installChannel: InstallChannel

/** Opens an external URL in the system browser. */
expect fun openInBrowser(url: String)

/**
 * Downloads the package at [url] through the platform and hands it to the OS
 * installer. Returns true when the hand-off started; false means this platform
 * cannot update in-process and the caller should fall back to the release
 * page in the browser.
 */
expect suspend fun downloadAndInstallUpdate(url: String): Boolean

/**
 * Reloads the running app so it picks up a newer build served by the same
 * origin. Meaningful on the web client (the server ships the bundle); on
 * packaged apps the OS restarts the process instead, so this is a no-op.
 */
expect fun reloadApp()
