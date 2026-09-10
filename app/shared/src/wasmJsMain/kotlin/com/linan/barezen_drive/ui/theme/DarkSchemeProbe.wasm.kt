package com.linan.barezen_drive.ui.theme

import androidx.compose.runtime.Composable
import kotlinx.browser.window

/** Reads the browser prefers-color-scheme media query. */
@Composable
actual fun prefersDarkSchemeWeb(): Boolean? = try {
    window.matchMedia("(prefers-color-scheme: dark)").matches
} catch (_: Throwable) {
    null
}
