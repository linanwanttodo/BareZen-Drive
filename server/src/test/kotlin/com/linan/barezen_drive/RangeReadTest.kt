package com.linan.barezen_drive

import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.openRange
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.test.*

/**
 * Byte ranges are the normal shape of media playback: a player seeks, the client
 * asks for the tail. Reading the object from byte 0 and discarding the prefix
 * made every one of those seeks move the whole prefix - through the page cache
 * locally, across the network on an object store.
 */
class RangeReadTest {
    private fun blob(size: Int) = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun openRangePositionsAtOffsetWithoutReading() {
        val root = Files.createTempDirectory("bz-range-open")
        val path = root.resolve("blob")
        val bytes = blob(1 shl 20)
        Files.write(path, bytes)
        val channel = openRange(path, 1 shl 19)
        try {
            // The position is already where the range starts before a single
            // byte is consumed, which is the whole point: nothing had to be read
            // to get here.
            assertEquals((1 shl 19).toLong(), channel.position())
            assertEquals(bytes.size.toLong(), channel.size())
            val buffer = ByteArray(16)
            val read = channel.read(java.nio.ByteBuffer.wrap(buffer))
            assertEquals(16, read)
            assertContentEquals(bytes.copyOfRange(1 shl 19, (1 shl 19) + 16), buffer)
        } finally {
            channel.close()
        }
    }

    @Test
    fun openRangeOnMissingBlobThrowsFileNotFound() {
        val root = Files.createTempDirectory("bz-range-missing")
        assertFailsWith<FileNotFoundException> { openRange(root.resolve("absent"), 10) }
    }

    @Test
    fun localRangeReadReturnsExactlyTheSlice() = runTest {
        val storage = LocalStorageProvider(Files.createTempDirectory("bz-range-local"))
        val bytes = blob(1 shl 20)
        storage.put("blob", ByteReadChannel(bytes))
        val tail = storage.getRange("blob", (1 shl 20) - 16, 1 shl 20).readRemaining().readByteArray()
        assertContentEquals(bytes.copyOfRange((1 shl 20) - 16, 1 shl 20), tail)
        val middle = storage.getRange("blob", 1000, 2000).readRemaining().readByteArray()
        assertContentEquals(bytes.copyOfRange(1000, 2000), middle)
        // A range that starts at the end is empty, not an error.
        assertEquals(0, storage.getRange("blob", bytes.size.toLong(), bytes.size.toLong()).readRemaining().readByteArray().size)
        // The whole-object path still returns everything.
        assertContentEquals(bytes, storage.get("blob").readRemaining().readByteArray())
    }

    @Test
    fun localRangeReadIsScopedToTheStorageRoot() = runTest {
        val storage = LocalStorageProvider(Files.createTempDirectory("bz-range-root"))
        assertFailsWith<IllegalArgumentException> { storage.getRange("../escape", 0, 10) }
    }
}
