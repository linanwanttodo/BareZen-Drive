package com.linan.barezen_drive.platform

import kotlinx.browser.window

actual fun monotonicNowMs(): Long = window.performance.now().toLong()
