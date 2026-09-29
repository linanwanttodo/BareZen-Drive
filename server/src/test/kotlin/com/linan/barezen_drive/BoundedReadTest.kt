package com.linan.barezen_drive

import com.linan.barezen_drive.api.readBounded
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The bounded body reader guards the avatar and any other raw upload endpoint:
 * an oversized request must be rejected while it streams, not after it has been
 * buffered. It used to grow a ByteArrayOutputStream from nothing (doubling on
 * every copy) and then call toByteArray(), which allocates a second full-size
 * array - so a 4 MiB avatar cost up to 12 MiB of live heap per request, on top
 * of the decoded image.
 *
 * The behaviour has to survive the rewrite: exact bytes under the limit, null
 * the moment the body crosses it, and a correct result for bodies far larger
 * than the read buffer.
 */
class BoundedReadTest {

    @Test
    fun readsTheWholeBodyWhenItIsUnderTheLimit() = runBlocking {
        val body = "hello avatar".encodeToByteArray()
        assertContentEquals(body, readBounded(ByteReadChannel(body), limit = 1024)!!)
    }

    @Test
    fun anEmptyBodyIsAnEmptyResult() = runBlocking {
        val out = readBounded(ByteReadChannel(ByteArray(0)), limit = 1024)
        assertEquals(0, out!!.size)
    }

    @Test
    fun aBodyExactlyAtTheLimitIsAccepted() = runBlocking {
        val body = ByteArray(200_000) { (it % 251).toByte() }
        val out = readBounded(ByteReadChannel(body), limit = body.size.toLong())
        assertContentEquals(body, out!!)
    }

    @Test
    fun oneByteOverTheLimitIsRefused() = runBlocking {
        val body = ByteArray(200_000) { (it % 251).toByte() }
        assertNull(readBounded(ByteReadChannel(body), limit = body.size - 1L))
    }

    /**
     * Bodies larger than the 64 KiB read buffer are the normal case for an
     * avatar: every buffer's worth has to be appended, in order, without a gap,
     * a repeat or a reordering.
     */
    @Test
    fun multiBufferBodiesAreConcatenatedInOrder() = runBlocking {
        val body = ByteArray(1_000_000) { (it * 31 % 253).toByte() }
        val out = readBounded(ByteReadChannel(body), limit = 4L * 1024 * 1024)!!
        assertEquals(body.size, out.size)
        assertContentEquals(body, out)
    }
}
