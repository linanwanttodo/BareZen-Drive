package com.linan.barezen_drive

import com.linan.barezen_drive.ui.screens.preview.FULL_PREVIEW_WEB_LIMIT
import com.linan.barezen_drive.ui.screens.preview.refusesFullPreview
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The full-size preview endpoint hands back the whole original, and the web
 * viewer held every byte of it in the JS heap before decoding. A 40 MB phone
 * photo is a hard crash in a browser tab, and swiping through a roll repeats it
 * once per photo.
 *
 * The gate is size and platform, so it has to stay shut for the two cases where
 * buffering is fine: a small original anywhere, and a large one on native, whose
 * decoder works lazily and off-heap.
 */
class ImagePreviewSizeTest {
    @Test
    fun webRefusesAnOversizedOriginal() {
        assertTrue(refusesFullPreview(FULL_PREVIEW_WEB_LIMIT + 1, webPlatform = true))
        assertTrue(refusesFullPreview(40L * 1024 * 1024, webPlatform = true))
    }

    @Test
    fun webStillPreviewsWhatAHeapCanHold() {
        assertFalse(refusesFullPreview(FULL_PREVIEW_WEB_LIMIT, webPlatform = true))
        assertFalse(refusesFullPreview(5L * 1024 * 1024, webPlatform = true))
    }

    @Test
    fun nativeIsNotGated() {
        assertFalse(refusesFullPreview(40L * 1024 * 1024, webPlatform = false))
    }

    @Test
    fun unknownSizeIsPreviewed() {
        // An absent content-length arrives as 0 and must not be read as "huge".
        assertFalse(refusesFullPreview(0L, webPlatform = true))
    }
}
