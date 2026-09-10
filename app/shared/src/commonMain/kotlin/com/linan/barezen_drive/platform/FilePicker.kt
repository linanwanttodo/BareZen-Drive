package com.linan.barezen_drive.platform

import androidx.compose.runtime.Composable

interface PickedFile {
    val name: String
    val size: Long
    val mimeType: String?

    /** Reads bytes in [offset, offset + length); returns null when out of bounds. */
    suspend fun readRange(offset: Long, length: Int): ByteArray?
}

@Composable
expect fun rememberFilePicker(onResult: (List<PickedFile>) -> Unit): () -> Unit
