package com.linan.barezen_drive.platform

/** Which distribution channel this build was installed through. */
enum class InstallChannel { ANDROID, IOS, DESKTOP, WEB }

/** True when an update must be fetched by opening the release page externally. */
val InstallChannel.updatesViaReleasePage: Boolean
    get() = this != InstallChannel.WEB

expect val installChannel: InstallChannel

/** Opens an external URL in the system browser. */
expect fun openInBrowser(url: String)

/**
 * Reloads the running app so it picks up a newer build served by the same
 * origin. Meaningful on the web client (the server ships the bundle); on
 * packaged apps the OS restarts the process instead, so this is a no-op.
 */
expect fun reloadApp()
