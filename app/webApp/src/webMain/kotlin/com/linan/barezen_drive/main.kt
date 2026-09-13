package com.linan.barezen_drive

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        WithFontResourcesLoaded {
            App()
        }
    }
}