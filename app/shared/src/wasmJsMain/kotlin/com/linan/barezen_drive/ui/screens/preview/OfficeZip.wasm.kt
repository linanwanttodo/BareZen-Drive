package com.linan.barezen_drive.ui.screens.preview

/**
 * Web preview of office files is not wired yet: DecompressionStream is a
 * promise-driven browser API that cannot be driven synchronously from this
 * call site. Returning null degrades to the viewer's "load failed" state -
 * the download button in the preview bar still works.
 */
internal actual object OfficeZip {
    actual fun inflateDeflate(data: ByteArray): ByteArray? = null
}
