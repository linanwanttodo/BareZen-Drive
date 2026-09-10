package com.linan.barezen_drive

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "BareZen-Drive",
    ) {
        App()
    }
}