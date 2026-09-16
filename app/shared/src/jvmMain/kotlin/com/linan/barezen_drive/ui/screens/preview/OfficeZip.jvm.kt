package com.linan.barezen_drive.ui.screens.preview

internal actual object OfficeZip {
    actual fun inflateDeflate(data: ByteArray): ByteArray? = runCatching {
        java.util.zip.InflaterInputStream(data.inputStream()).use { it.readBytes() }
    }.getOrNull()
}
