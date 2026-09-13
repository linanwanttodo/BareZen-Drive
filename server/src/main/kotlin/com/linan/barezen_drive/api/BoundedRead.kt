package com.linan.barezen_drive.api

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Reads the request body but at most [limit] bytes. Unlike receive<ByteArray>()
 * (which buffers the whole body first and only checks afterwards), this stops
 * consuming and returns null as soon as the client streams past the limit, so
 * an oversized body - with or without a Content-Length header - never lands on
 * the heap.
 */
suspend fun readBounded(body: ByteReadChannel, limit: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteBuffer.allocate(64 * 1024)
    var total = 0L
    while (true) {
        buf.clear()
        val n = body.readAvailable(buf)
        if (n < 0) break
        if (n > 0) {
            total += n
            if (total > limit) return null
            buf.flip()
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            out.write(bytes)
        }
    }
    return out.toByteArray()
}
