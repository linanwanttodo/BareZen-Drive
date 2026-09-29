package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * A local `complete()` used to write the merged file three times over: the merge
 * wrote it once, `storage.put` streamed it through a second write, and the
 * content-addressed key made the third write a no-op only by accident. Reading it
 * cost two full passes and the peak disk footprint was ~3x the file.
 *
 * The merge result and the blob live on the same volume, so the third write is
 * only ever a rename. These tests pin that the bytes reach the blob key without
 * being streamed through the storage channel again, that the rename is not the
 * only way in (a backend whose blob path cannot be renamed into must still
 * upload), and that neither path changes what ends up stored.
 */
class UploadBlobPromotionTest {
    private val storageDir = Files.createTempDirectory("bz-promote").toString()
    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
        // Stated explicitly: registration is closed by default, and the
        // bootstrap slot only opens for a loopback peer, so a test that
        // registers must say it wants the door open.
        registrationOpen = true,
    )
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""

    /**
     * Counts how many times bytes are streamed in for a blob key. A cover key is
     * deliberately not counted: background cover generation may store one at any
     * moment, and it says nothing about how the file itself got published.
     */
    private class CountingStorage(
        private val inner: LocalStorageProvider,
        /** When set, blob keys resolve under a regular file, so a rename into
         *  them cannot succeed (a cross-device or otherwise unmovable backend). */
        private val unmovableBlobRoot: Path? = null,
    ) : StorageProvider {
        val blobPuts = AtomicInteger()

        override fun blobKey(sha256: String): String = inner.blobKey(sha256)
        override suspend fun put(key: String, channel: ByteReadChannel) {
            if (key.startsWith("blobs/")) blobPuts.incrementAndGet()
            inner.put(key, channel)
        }

        override suspend fun get(key: String): ByteReadChannel = inner.get(key)
        override suspend fun delete(key: String) = inner.delete(key)
        override suspend fun exists(key: String): Boolean = inner.exists(key)
        override val tmpDir: Path get() = inner.tmpDir
        override fun resolvePath(key: String): Path =
            if (unmovableBlobRoot != null && key.startsWith("blobs/")) unmovableBlobRoot.resolve(key) else inner.resolvePath(key)
    }

    private suspend fun ApplicationTestBuilder.setup(storage: StorageProvider) {
        val c = cfg()
        application { module(c, storage) }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    private fun sha256hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** 2.5 MiB: three chunks at a 1 MiB chunk size. */
    private fun body(): ByteArray = ByteArray(2 * 1024 * 1024 + 512 * 1024) { (it % 251).toByte() }

    private suspend fun ApplicationTestBuilder.uploadWhole(name: String, body: ByteArray): String {
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"$name","size":${body.size},"chunkSize":${1024 * 1024},"sha256":"${sha256hex(body)}"}""",
            )
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        assertFalse(ir.instantUpload, "the blob must not exist yet, or this measures nothing")
        var index = 0
        while (index * ir.chunkSize < body.size) {
            val from = (index * ir.chunkSize).toInt()
            val to = minOf((index + 1) * ir.chunkSize, body.size.toLong()).toInt()
            val put = client.put("/api/uploads/${ir.uploadId}/chunks/$index") {
                header(HttpHeaders.Authorization, auth); setBody(body.copyOfRange(from, to))
            }
            assertEquals(HttpStatusCode.NoContent, put.status, "chunk $index: ${put.bodyAsText()}")
            index++
        }
        val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
    }

    @Test
    fun completePublishesTheMergeByRenameWithoutStreamingItThroughStorageAgain() = testApplication {
        val local = LocalStorageProvider(Path.of(storageDir))
        val storage = CountingStorage(local)
        setup(storage)
        val body = body()

        val id = uploadWhole("movie.bin", body)

        assertEquals(
            0, storage.blobPuts.get(),
            "the merge result must reach the blob key by rename; streaming it back in " +
                "means a second full read and a second full write of the file",
        )
        // What landed must be exactly the bytes that were uploaded...
        val content = client.get("/api/files/$id/content") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, content.status, content.bodyAsText())
        assertEquals(sha256hex(body), sha256hex(content.bodyAsBytes()), "the stored bytes are not the uploaded bytes")
        // ...at the content-addressed key, and the merge scratch file is gone.
        assertTrue(Files.exists(local.resolvePath(local.blobKey(sha256hex(body)))))
        val leftovers = Files.list(local.tmpDir).use { s -> s.filter { it.fileName.toString().startsWith("merge-") }.count() }
        assertEquals(0L, leftovers, "the merge file must not survive a rename-based publish")
    }

    @Test
    fun anUnmovableBlobPathStillUploadsThroughTheCopyingFallback() = testApplication {
        val local = LocalStorageProvider(Path.of(storageDir))
        // A regular file where the blob's parent directory would have to be: the
        // rename cannot succeed, so the upload has to fall back to streaming.
        val blocker = Files.write(Path.of(storageDir, "blocker"), byteArrayOf(1))
        val storage = CountingStorage(local, unmovableBlobRoot = blocker)
        setup(storage)
        val body = body()

        val id = uploadWhole("fallback.bin", body)

        assertEquals(1, storage.blobPuts.get(), "the fallback must stream the merge into the blob exactly once")
        val content = client.get("/api/files/$id/content") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, content.status, content.bodyAsText())
        assertEquals(sha256hex(body), sha256hex(content.bodyAsBytes()))
    }
}
