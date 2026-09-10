package com.linan.barezen_drive.platform

/**
 * Generates a JPEG cover image for uploadable media. Returns null when the
 * file type has no cover (or generation failed) - callers must treat that as
 * "skip the thumbnail", never as an upload failure.
 *
 * Contract: JPEG bytes, longest edge <= 512px, body <= 512KB.
 */
expect suspend fun generateCover(file: PickedFile): ByteArray?

/** True when this file type gets a cover image (images and videos). */
fun hasCover(file: PickedFile): Boolean {
    val mime = file.mimeType ?: return file.name.endsWith(".mp4", ignoreCase = true) ||
        file.name.endsWith(".mov", ignoreCase = true) || file.name.endsWith(".webm", ignoreCase = true)
    return mime.startsWith("image/") || mime.startsWith("video/")
}
