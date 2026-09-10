package com.linan.barezen_drive

import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class StorageTest {
    private fun sha(n: Int) = n.toString(16).padStart(64, '0')

    @Test
    fun putGetDeleteRoundTrip() = runTest {
        val storage = LocalStorageProvider(Files.createTempDirectory("st"))
        val key = storage.blobKey(sha(1))
        assertFalse(storage.exists(key))
        storage.put(key, ByteReadChannel("hello blob".encodeToByteArray()))
        assertTrue(storage.exists(key))
        assertEquals("hello blob", storage.get(key).toByteArray().decodeToString())
        storage.delete(key)
        assertFalse(storage.exists(key))
    }

    @Test
    fun putTwiceSkipsRewrite() = runTest {
        val storage = LocalStorageProvider(Files.createTempDirectory("st"))
        val key = storage.blobKey(sha(2))
        storage.put(key, ByteReadChannel("v1".encodeToByteArray()))
        storage.put(key, ByteReadChannel("V2-DIFFERENT".encodeToByteArray())) // exists -> discard, no overwrite
        assertEquals("v1", storage.get(key).toByteArray().decodeToString())
    }
}
