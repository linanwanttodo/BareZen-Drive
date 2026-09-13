package com.linan.barezen_drive.platform

import androidx.compose.runtime.Composable
import io.ktor.utils.io.ByteReadChannel

@Composable
expect fun rememberFileSaver(onDone: (String?) -> Unit): (name: String, mime: String?, open: suspend () -> ByteReadChannel) -> Unit
