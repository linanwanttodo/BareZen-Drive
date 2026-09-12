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
