package com.linan.barezen_drive.platform

import androidx.compose.runtime.Composable

interface PickedFile {
    val name: String
    val size: Long
    val mimeType: String?

    /**
     * Source phone-album name (Camera, Screenshots, WeiXin...), when the
     * platform knows it (Android MediaStore bucket). Category folders in the
     * album tree are created from this on demand; null = no category layer
     * (web/desktop uploads land directly in the device folder).
     */
    val originAlbum: String? get() = null

    /** Reads bytes in [offset, offset + length); returns null when out of bounds. */
    suspend fun readRange(offset: Long, length: Int): ByteArray?
}

@Composable
expect fun rememberFilePicker(onResult: (List<PickedFile>) -> Unit): () -> Unit
