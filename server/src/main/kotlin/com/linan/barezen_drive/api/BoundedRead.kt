package com.linan.barezen_drive.api

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.nio.ByteBuffer

/**
 * Reads the request body but at most [limit] bytes. Unlike receive<ByteArray>()
 * (which buffers the whole body first and only checks afterwards), this stops
 * consuming and returns null as soon as the client streams past the limit, so
 * an oversized body - with or without a Content-Length header - never lands on
 * the heap.
 *
 * The result is assembled in a single array that is filled as the body arrives.
 * Growing a ByteArrayOutputStream and calling toByteArray() at the end costs
 * roughly three times the body in allocations for a 4 MiB upload - the doubling
 * copies during growth, then one more full copy - and all of them are live at
 * once, which is the wrong trade on a 1C box.
 *
 * [initialCapacity] is the caller's Content-Length when it has one: the exact
 * answer, so the common case allocates once and copies nothing. It is only a
 * hint - a client may lie, so it is clamped and still checked against [limit].
 */
suspend fun readBounded(body: ByteReadChannel, limit: Long, initialCapacity: Long = 0): ByteArray? {
    val declared = initialCapacity.takeIf { it in 1..minOf(limit, MAX_PREALLOC.toLong()) }?.toInt()
    var out = ByteArray(declared ?: DEFAULT_PREALLOC)
    var filled = 0
    val buf = ByteBuffer.allocate(READ_CHUNK)
    while (true) {
        buf.clear()
        val n = body.readAvailable(buf)
        if (n < 0) break
        if (n == 0) continue
        val total = filled.toLong() + n
        if (total > limit) return null
        if (total > out.size) {
            // Grow geometrically: amortised linear, and one copy per doubling
            // rather than one per chunk.
            val doubled = out.size.toLong() * 2
            val capacity = if (doubled < 0) limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            else maxOf(doubled, total).coerceAtMost(limit.coerceAtMost(Int.MAX_VALUE.toLong())).toInt()
            out = out.copyOf(capacity)
        }
        buf.flip()
        buf.get(out, filled, n)
        filled += n
    }
    return if (filled == out.size) out else out.copyOf(filled)
}

/** Read granularity; also the floor for the initial allocation. */
private const val READ_CHUNK = 64 * 1024

/** Body sizes below this are buffered without asking for a length first. */
private const val DEFAULT_PREALLOC = 8 * 1024

/**
 * Never pre-allocate more than this from a declared length: the header is
 * attacker-controlled, and a request that lies about its size must not be able
 * to reserve an arbitrary array.
 */
private const val MAX_PREALLOC = 4 * 1024 * 1024
