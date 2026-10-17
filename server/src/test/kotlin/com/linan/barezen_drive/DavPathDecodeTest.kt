package com.linan.barezen_drive

import com.linan.barezen_drive.webdav.davSegments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Percent-decoding of a `/dav` path segment.
 *
 * The overflow this pins: the decode buffer was sized by *character* count, while
 * a raw non-ASCII character costs up to three UTF-8 bytes. A client that sends a
 * stray percent sign (kept literal) followed by a Chinese file name wrote past the
 * end of the buffer, and the request died with a 500 instead of naming a file.
 * Anything that reaches the decoder through an escape sequence is shorter than its
 * own encoding, so only the raw characters can overflow it.
 *
 * The second case belongs to the same branch: a surrogate pair is one code point
 * split over two characters, so encoding each half alone yields two replacement
 * characters and the path silently names a *different* file than the one asked for.
 */
class DavPathDecodeTest {
    @Test
    fun rawNonAsciiAfterALiteralPercentDecodes() {
        assertEquals(listOf("100%报告.docx"), davSegments("/100%报告.docx"))
        // A whole directory of them, which is what a mount lists.
        assertEquals(listOf("相册", "50%备忘.txt"), davSegments("/相册/50%备忘.txt"))
    }

    @Test
    fun astralCharactersSurviveAsThemselves() {
        // U+1D4EA is outside the BMP, so it is two chars in a Kotlin string and
        // four bytes in UTF-8.
        assertEquals(listOf("100%𝓪.log"), davSegments("/100%𝓪.log"))
        assertEquals(listOf("𝓪.log"), davSegments("/𝓪.log"))
    }

    @Test
    fun escapedSegmentsStillDecodeAndSeparatorsStillRefuse() {
        assertEquals(listOf("报告.txt"), davSegments("/%E6%8A%A5%E5%91%8A.txt"))
        assertNull(davSegments("/a%2Fb"), "a hidden separator is not a name")
        // A percent the client never meant to escape is part of the name, and so
        // is the one it did escape: both spellings name the same file.
        assertEquals(listOf("%"), davSegments("/%25"))
        assertEquals(listOf("%"), davSegments("/%"))
    }

    @Test
    fun anUnpairedSurrogateNamesNothing() {
        // Half a code point is not a character any client can mean, and the
        // alternative is inventing a name for it.
        assertNull(davSegments("/" + "\uD83D" + ".jpg"))
        assertNull(davSegments("/" + "\uDE00" + ".jpg"))
    }
}
