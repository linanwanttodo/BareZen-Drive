package com.linan.barezen_drive

import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.StorageRef
import com.linan.barezen_drive.storage.StorageRegistry
import io.ktor.utils.io.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The storage registry is the reserved seam for a multi-node deployment: refs
 * carry an optional backend name, bare keys keep resolving to the first
 * registered provider, and objects can be streamed between backends. None of
 * the request paths look a backend up yet, so this suite pins the interface
 * contract a cluster implementation will have to satisfy.
 */
class StorageRegistryTest {

    @AfterTest
    fun tearDown() = StorageRegistry.reset()

    private fun tempProvider(): LocalStorageProvider =
        LocalStorageProvider(Files.createTempDirectory("bz-reg"))

    @Test
    fun bareKeyHasNoBackendAndRoundTrips() {
        val ref = StorageRef.parse("blobs/aa/bb/cc")
        assertNull(ref.backend)
        assertEquals("blobs/aa/bb/cc", ref.key)
        assertEquals("blobs/aa/bb/cc", ref.serialize())
    }

    @Test
    fun prefixedKeyParsesIntoBackendAndKey() {
        val ref = StorageRef.parse("s3-a:blobs/aa/bb/cc")
        assertEquals("s3-a", ref.backend)
        assertEquals("blobs/aa/bb/cc", ref.key)
        assertEquals("s3-a:blobs/aa/bb/cc", ref.serialize())
    }

    @Test
    fun firstRegistrationBecomesDefaultAndBareKeysResolveToIt() {
        val first = tempProvider()
        val second = tempProvider()
        StorageRegistry.register("local", first)
        StorageRegistry.register("s3", second)
        assertSame(first, StorageRegistry.defaultProvider())
        assertSame(first, StorageRegistry.resolve("blobs/xx").first)
        assertSame(second, StorageRegistry.resolve("s3:blobs/xx").first)
        // The key handed back is the storage-local part, without the prefix.
        assertEquals("blobs/xx", StorageRegistry.resolve("s3:blobs/xx").second)
        assertEquals(setOf("local", "s3"), StorageRegistry.backends())
    }

    @Test
    fun unknownBackendIsAnErrorNotASilentFallback() {
        StorageRegistry.register("local", tempProvider())
        val e = assertFailsWith<IllegalStateException> { StorageRegistry.resolve("node2:blobs/xx") }
        assertTrue(e.message!!.contains("node2"), "the message must name the missing backend")
    }

    @Test
    fun resolvingWithNothingRegisteredFails() {
        assertNull(StorageRegistry.defaultProvider())
        assertFailsWith<IllegalStateException> { StorageRegistry.resolve("blobs/xx") }
    }

    @Test
    fun backendNameMustNotContainTheSeparator() {
        assertFailsWith<IllegalArgumentException> { StorageRegistry.register("a:b", tempProvider()) }
        assertFailsWith<IllegalArgumentException> { StorageRegistry.register("  ", tempProvider()) }
    }

    @Test
    fun copyObjectMovesBytesBetweenBackends() = runTest {
        val from = tempProvider()
        val to = tempProvider()
        val key = from.blobKey("f".repeat(64))
        from.put(key, ByteReadChannel("payload".encodeToByteArray()))
        StorageRegistry.copyObject(from, to, key)
        assertTrue(to.exists(key), "target backend must hold the object after the copy")
        assertEquals("payload", to.get(key).toByteArray().decodeToString())
        // Streaming a copy never deletes the source: rows still point at it.
        assertTrue(from.exists(key))
    }
}
