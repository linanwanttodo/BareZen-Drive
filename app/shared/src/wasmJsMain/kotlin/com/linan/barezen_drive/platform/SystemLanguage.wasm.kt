package com.linan.barezen_drive.platform

import kotlinx.browser.window

actual fun systemLanguageTag(): String =
    runCatching { window.navigator.language }.getOrDefault("en")
