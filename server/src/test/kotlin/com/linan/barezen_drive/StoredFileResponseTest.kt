package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.ShareDto
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Both content routes - the signed/authenticated `/api/files/{id}/content` and
 * the token-only public share `/api/public/shares/{token}/files/{fid}/content` -
 * stream a blob straight out of the storage provider. Two things about that
 * stream are pinned here:
 *
 *  1. the blob must not be opened until the response is known to carry a body.
 *     ktor's PartialContent plugin answers an unsatisfiable `Range` by replacing
 *     the outgoing content with a 416 and never touching the body, so a blob
 *     opened before that point is a file descriptor nobody closes;
 *  2. once a body *is* written, the blob channel must be closed - including the
 *     206 path, where ktor's default `readFrom(range)` copies from the source
 *     into a new channel and never closes the source itself.
 *
 * The two routes are also asserted to answer identically for the same stored
 * file: they used to be two hand-written copies of the same three steps, and any
 * fix to one of them silently missed the other.
 */
class StoredFileResponseTest {
    private val storageDir = Files.createTempDirectory("bz-stored").toString()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Wraps the real provider and records every channel `get()` hands out.
     *
     * The probe is the [cancel] call rather than the channel's own closed flag:
     * a range response stops mid-file, so the channel can be left holding
     * undecoded bytes when the copy ends, and `isClosedForRead` stays false even
     * though the file descriptor behind it is already released. Cancelling is
     * the contract the response owes the storage provider.
     */
    private class RecordingStorage(private val delegate: StorageProvider) : StorageProvider {
        val gets = AtomicInteger()
        val opened = CopyOnWriteArrayList<TrackedChannel>()

        override fun blobKey(sha256: String) = delegate.blobKey(sha256)
        override suspend fun put(key: String, channel: ByteReadChannel) = delegate.put(key, channel)

        override suspend fun get(key: String): ByteReadChannel {
            gets.incrementAndGet()
            return TrackedChannel(delegate.get(key)).also { opened += it }
        }

        override suspend fun delete(key: String) = delegate.delete(key)
        override suspend fun exists(key: String) = delegate.exists(key)
        override fun resolvePath(key: String): Path? = delegate.resolvePath(key)
        override val tmpDir: Path get() = delegate.tmpDir
    }

    private class TrackedChannel(private val delegate: ByteReadChannel) : ByteReadChannel by delegate {
        val released = AtomicBoolean(false)
        override fun cancel(cause: Throwable?) {
            released.set(true)
            delegate.cancel(cause)
        }
    }

    private lateinit var storage: RecordingStorage
    private var auth = ""

    private suspend fun ApplicationTestBuilder.setup() {
        val c = AppConfig(
            0,
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "",
            "test-secret-0123456789abcdef0123456789abcdef",
            storageDir, 1L shl 30,
            // Registration is closed by default on a fresh instance; these tests
            // are not about that, so they open it explicitly.
            registrationOpen = true,
        )
        storage = RecordingStorage(LocalStorageProvider(Path.of(c.storageDir)))
        application { module(c, storage) }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    private fun sha256hex(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private suspend fun ApplicationTestBuilder.upload(body: ByteArray, name: String, mime: String? = null): String {
        val mimeField = mime?.let { ""","mimeType":"$it"""" } ?: ""
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"sha256":"${sha256hex(body)}"$mimeField}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        if (!ir.instantUpload) {
            var i = 0
            while (i * ir.chunkSize < body.size) {
                val from = (i * ir.chunkSize).toInt()
                val to = minOf((i + 1) * ir.chunkSize, body.size.toLong()).toInt()
                val put = client.put("/api/uploads/${ir.uploadId}/chunks/$i") {
                    header(HttpHeaders.Authorization, auth)
                    setBody(body.copyOfRange(from, to))
                }
                assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
                i++
            }
            val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
            assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
            return json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
        }
        return json.decodeFromString<UploadCompleteResponse>(init.bodyAsText()).file.id
    }

    private suspend fun ApplicationTestBuilder.shareOf(fileId: String): String {
        val res = client.post("/api/shares") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"fileId":"$fileId"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        val dto = json.decodeFromString<ShareDto>(res.bodyAsText())
        return dto.url.removePrefix("/s/")
    }

    /** Waits (briefly) for the server-side copy coroutine to release the blob. */
    private fun awaitReleased(channels: List<TrackedChannel>, what: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (channels.isNotEmpty() && channels.all { it.released.get() }) return
            Thread.sleep(10)
        }
        assertTrue(
            channels.all { it.released.get() },
            "$what left ${channels.count { !it.released.get() }} of ${channels.size} blob channel(s) open",
        )
    }

    // ---- 416 must not touch the blob at all --------------------------------

    @Test
    fun unsatisfiableRangeNeverOpensTheBlob() = testApplication {
        setup()
        val id = upload("0123456789".encodeToByteArray(), "small.txt")
        val token = shareOf(id)

        val authed = client.get("/api/files/$id/content") {
            header(HttpHeaders.Authorization, auth)
            header(HttpHeaders.Range, "bytes=500-600")
        }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, authed.status, authed.bodyAsText())
        assertEquals("bytes */10", authed.headers[HttpHeaders.ContentRange])

        val anon = client.get("/api/public/shares/$token/files/$id/content") {
            header(HttpHeaders.Range, "bytes=500-600")
        }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, anon.status, anon.bodyAsText())

        assertEquals(
            0, storage.gets.get(),
            "a rejected Range must be refused before the blob is opened, " +
                "otherwise every 416 parks a file descriptor until the GC runs",
        )
    }

    // ---- the 206 path must close what it opened ----------------------------

    @Test
    fun partialRangeReleasesTheBlobChannel() = testApplication {
        setup()
        val id = upload("0123456789".encodeToByteArray(), "ranged.txt")
        val token = shareOf(id)

        val res = client.get("/api/files/$id/content") {
            header(HttpHeaders.Authorization, auth)
            header(HttpHeaders.Range, "bytes=2-5")
        }
        assertEquals(HttpStatusCode.PartialContent, res.status, res.bodyAsText())
        assertEquals("2345", res.bodyAsText())
        assertEquals("bytes 2-5/10", res.headers[HttpHeaders.ContentRange])
        assertEquals(1, storage.gets.get())
        awaitReleased(storage.opened.toList(), "a 206 that stops mid-file")

        val anon = client.get("/api/public/shares/$token/files/$id/content") {
            header(HttpHeaders.Range, "bytes=2-5")
        }
        assertEquals(HttpStatusCode.PartialContent, anon.status, anon.bodyAsText())
        assertEquals("2345", anon.bodyAsText())
        assertEquals(2, storage.gets.get())
        awaitReleased(storage.opened.toList(), "a public 206 that stops mid-file")
    }

    @Test
    fun fullDownloadReleasesTheBlobChannel() = testApplication {
        setup()
        val id = upload("hello world".encodeToByteArray(), "whole.txt")
        val token = shareOf(id)

        val res = client.get("/api/files/$id/content") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals("hello world", res.bodyAsText())
        assertEquals(11, res.headers[HttpHeaders.ContentLength]?.toInt())

        val anon = client.get("/api/public/shares/$token/files/$id/content")
        assertEquals(HttpStatusCode.OK, anon.status, anon.bodyAsText())
        assertEquals("hello world", anon.bodyAsText())

        assertEquals(2, storage.gets.get())
        awaitReleased(storage.opened.toList(), "a completed 200")
    }

    // ---- one shared responder, so the two routes cannot drift --------------

    @Test
    fun bothContentRoutesAnswerIdentically() = testApplication {
        setup()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        val html = "<html><script>fetch('/api/me')</script></html>".encodeToByteArray()
        val pngId = upload(png, "same.png", "image/png")
        val htmlId = upload(html, "same.html", "text/html")
        val pngToken = shareOf(pngId)
        val htmlToken = shareOf(htmlId)

        data class Case(val id: String, val token: String, val type: ContentType, val attachment: Boolean)

        for (c in listOf(
            Case(pngId, pngToken, ContentType.Image.PNG, false),
            Case(htmlId, htmlToken, ContentType.Application.OctetStream, true),
        )) {
            val a = client.get("/api/files/${c.id}/content") { header(HttpHeaders.Authorization, auth) }
            val b = client.get("/api/public/shares/${c.token}/files/${c.id}/content")
            assertEquals(HttpStatusCode.OK, a.status, a.bodyAsText())
            assertEquals(a.status, b.status)
            assertEquals(a.contentType()?.withoutParameters(), b.contentType()?.withoutParameters())
            assertEquals(c.type, b.contentType()?.withoutParameters())
            assertEquals(
                a.headers[HttpHeaders.ContentDisposition],
                b.headers[HttpHeaders.ContentDisposition],
                "the two content routes must apply the same content-type policy",
            )
            assertEquals(
                c.attachment,
                b.headers[HttpHeaders.ContentDisposition]?.startsWith("attachment", ignoreCase = true) == true,
            )
            assertEquals(a.bodyAsText(), b.bodyAsText())
            assertEquals(a.headers[HttpHeaders.ContentLength], b.headers[HttpHeaders.ContentLength])
            assertEquals(a.headers[HttpHeaders.AcceptRanges], b.headers[HttpHeaders.AcceptRanges])
        }
    }

    @Test
    fun missingBlobAnswers404WithoutOpening() = testApplication {
        setup()
        val id = upload("will be pruned".encodeToByteArray(), "gone.txt")
        val token = shareOf(id)
        // The row survives a manual store prune; the blob does not.
        val key = transaction(DatabaseFactory.db) {
            FilesTable.selectAll().where { FilesTable.id eq UUID.fromString(id) }
                .single()[FilesTable.storageKey]
        }
        Files.delete(Path.of(storageDir).resolve(key))

        val a = client.get("/api/files/$id/content") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NotFound, a.status, a.bodyAsText())
        val b = client.get("/api/public/shares/$token/files/$id/content")
        assertEquals(HttpStatusCode.NotFound, b.status, b.bodyAsText())
        assertEquals(0, storage.gets.get(), "a missing payload must not be opened either")
    }
}
