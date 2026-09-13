package com.linan.barezen_drive.platform

import kotlinx.browser.window

/** Parses /s/<token> from the address bar; any other path returns null. */
actual fun initialShareToken(): String? {
    val path = window.location.pathname
    if (!path.startsWith("/s/")) return null
    val token = path.removePrefix("/s/").trimEnd('/')
    return token.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
}
