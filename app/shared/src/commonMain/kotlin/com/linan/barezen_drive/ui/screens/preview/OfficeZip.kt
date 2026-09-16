package com.linan.barezen_drive.ui.screens.preview

/** Raw DEFLATE inflation, per platform (see OfficeExtract.kt). */
internal expect object OfficeZip {
    fun inflateDeflate(data: ByteArray): ByteArray?
}
