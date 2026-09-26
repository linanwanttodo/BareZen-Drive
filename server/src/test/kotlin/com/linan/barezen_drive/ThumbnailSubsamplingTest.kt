package com.linan.barezen_drive

import com.linan.barezen_drive.files.ThumbnailService
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Covers are 512px, but the ImageIO fallback used to decode the *whole* source
 * raster and only then scale it down. The guard above it allows 64MP, which is
 * 192-256MB of heap for a single image - on a 1G host (default JVM heap ~256MB)
 * one phone photo OOMs the server, and the OOM takes the upload path and every
 * other request with it.
 *
 * Decoding a subsampled grid instead bounds the working set by the factor, and
 * these tests pin that bound: the factor must shrink the decoded pixel count
 * into a sane range while still leaving enough pixels for a 512px cover, and it
 * must be the same on both axes or the photo comes out distorted.
 */
class ThumbnailSubsamplingTest {

    private fun decodedEdge(edge: Long, factor: Int) = ceil(edge.toDouble() / factor).toLong()

    @Test
    fun smallImagesDecodeAtFullSize() {
        assertEquals(1 to 1, ThumbnailService.subsamplingFor(400, 300, 512))
        assertEquals(1 to 1, ThumbnailService.subsamplingFor(512, 512, 512))
    }

    @Test
    fun largeImagesAreDecimated() {
        // 24MP phone photo: factor 8 leaves a 750px long edge - still above the
        // 512px cover, so the result is downscaled rather than upscaled.
        assertEquals(8 to 8, ThumbnailService.subsamplingFor(6000, 4000, 512))
        // 64MP, the current guard ceiling: 8000/8 = 1000px, ~4MB of heap
        // instead of the ~256MB a full decode would need.
        assertEquals(8 to 8, ThumbnailService.subsamplingFor(8000, 8000, 512))
    }

    @Test
    fun bothAxesUseTheSameFactorSoTheAspectSurvives() {
        for ((w, h) in listOf(6000L to 4000L, 4000L to 6000L, 12000L to 1000L, 900L to 8000L)) {
            val (fx, fy) = ThumbnailService.subsamplingFor(w, h, 512)
            assertEquals(fx, fy, "non-uniform subsampling distorts $w x $h")
        }
    }

    @Test
    fun decodedPixelsStayBoundedAcrossTheAllowedRange() {
        var worst = 0L
        for (w in listOf(320L, 1024L, 2048L, 4000L, 6000L, 8000L)) {
            for (h in listOf(240L, 768L, 3000L, 4000L, 8000L)) {
                if (w * h > 64L * 1024 * 1024) continue
                val (fx, fy) = ThumbnailService.subsamplingFor(w, h, 512)
                val pixels = decodedEdge(w, fx) * decodedEdge(h, fy)
                worst = max(worst, pixels)
                assertTrue(
                    pixels <= 4L * 1024 * 1024,
                    "${w}x$h decodes to $pixels pixels at ${fx}x$fy - too much heap",
                )
                // Never decode less than the cover needs unless the source is
                // smaller than the cover in the first place.
                val longEdge = max(w, h)
                if (longEdge > 512) {
                    assertTrue(
                        decodedEdge(longEdge, fx) >= 512,
                        "${w}x$h decimated to ${decodedEdge(longEdge, fx)}px, below the 512px cover",
                    )
                }
            }
        }
        assertTrue(worst > 0)
    }

    @Test
    fun theFactorIsAPowerOfTwoBecauseNotEveryReaderHonoursOthers() {
        for (size in listOf(600L, 1200L, 3000L, 6000L, 9000L, 20000L)) {
            val (fx, fy) = ThumbnailService.subsamplingFor(size, size, 512)
            for (f in listOf(fx, fy)) {
                assertEquals(0, f and (f - 1), "factor $f for ${size}px is not a power of two")
                assertTrue(f in 1..16, "factor $f for ${size}px is out of range")
            }
        }
    }

    /**
     * End-to-end sanity: the helper's factor has to be usable by a real reader,
     * otherwise the decimation silently does nothing and we are back to a full
     * decode. Decodes a real (small but oversampled) JPEG through the same
     * ImageReadParam path the service uses.
     */
    @Test
    fun subsamplingParameterActuallyShrinksWhatTheReaderReturns() {
        val src = BufferedImage(2048, 1536, BufferedImage.TYPE_INT_RGB)
        val g = src.createGraphics()
        g.color = Color.BLUE
        g.fillRect(0, 0, 2048, 1536)
        g.dispose()
        val bytes = ByteArrayOutputStream().also { ImageIO.write(src, "jpg", it) }.toByteArray()
        val file = kotlin.io.path.createTempFile(suffix = ".jpg").toFile()
        file.writeBytes(bytes)

        val input = ImageIO.createImageInputStream(file)
        val reader = ImageIO.getImageReaders(input).next()
        reader.input = input
        try {
            val (fx, fy) = ThumbnailService.subsamplingFor(2048, 1536, 512)
            val param = reader.defaultReadParam.apply { setSourceSubsampling(fx, fy, 0, 0) }
            val decoded = reader.read(0, param)
            assertEquals(2048 / fx, decoded.width, "the reader ignored the subsampling factor")
            assertEquals(1536 / fy, decoded.height, "the reader ignored the subsampling factor")
        } finally {
            reader.dispose()
            input.close()
            file.delete()
        }
    }

    /** The cover is square-bounded by the long edge, matching the old scaling. */
    @Test
    fun targetEdgeDrivesTheFactor() {
        assertEquals(1 to 1, ThumbnailService.subsamplingFor(2000, 2000, 1024))
        assertEquals(2 to 2, ThumbnailService.subsamplingFor(4000, 4000, 1024))
        assertEquals(1 to 1, ThumbnailService.subsamplingFor(1000, 1000, 512))
        assertTrue(min(2048, 2048) > 0)
    }
}
