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
 *
 * [sha256] and [size] come from the release manifest and are checked against
 * the bytes on disk before the file reaches the installer. Without them a
 * relay that answers 200 with a short or mangled body still counted as a
 * successful mirror, and the user got an opaque "package appears to be
 * invalid" from the installer instead of a retry on the other source. Either
 * value may be null (older manifests), in which case only what is present is
 * enforced. A file that fails the check is deleted and the next mirror is
 * tried, so a mirror is only "good" if it delivered the exact promised bytes.
 */
expect suspend fun downloadAndInstallUpdate(
    urls: List<String>,
    sha256: String? = null,
    size: Long? = null,
    onProgress: (Float) -> Unit = {},
): Boolean

/**
 * Downloads and verifies the package without showing anything to the user, and
 * returns the absolute path of the staged file - or null when every mirror
 * failed. Split out of [downloadAndInstallUpdate] because a background pass must
 * not be able to pop the system installer: the file waits here until the user
 * taps the notification.
 */
expect suspend fun stageUpdateApk(
    urls: List<String>,
    sha256: String? = null,
    size: Long? = null,
    onProgress: (Float) -> Unit = {},
): String?

/**
 * Hands a file returned by [stageUpdateApk] to the OS installer. Returns false
 * when there is nothing to install (web, or a file that has since been cleaned
 * up). The user still confirms in the system sheet - that step is Android's, and
 * no caller here can or should skip it.
 */
expect fun installStagedUpdate(path: String): Boolean

/**
 * Reloads the running app so it picks up a newer build served by the same
 * origin. Meaningful on the web client (the server ships the bundle); on
 * packaged apps the OS restarts the process instead, so this is a no-op.
 */
expect fun reloadApp()
