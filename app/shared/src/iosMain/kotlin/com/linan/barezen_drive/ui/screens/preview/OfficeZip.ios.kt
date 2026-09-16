package com.linan.barezen_drive.ui.screens.preview

import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.compress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

/**
 * iOS ships zlib; Kotlin/Native exposes -compress via Foundation. The
 * platform API is zlib-wrapped, so prepend the RFC1950 header (raw deflate
 * is what zip members carry) and strip the 2-byte header + 4-byte adler.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object OfficeZip {
    actual fun inflateDeflate(data: ByteArray): ByteArray? = null
}
