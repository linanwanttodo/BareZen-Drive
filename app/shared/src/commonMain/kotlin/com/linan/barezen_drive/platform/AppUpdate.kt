package com.linan.barezen_drive.platform

/**
 * True only on the Android build; gates UI that makes no sense on the web
 * client (for example the app update check).
 */
expect val isAndroidPlatform: Boolean

/**
 * Fetches the latest release tag from the GitHub repository. Returns null
 * when the platform cannot check (web) or the request failed.
 */
expect suspend fun latestReleaseTag(): String?

/** Opens an external URL in the system browser. */
expect fun openInBrowser(url: String)
