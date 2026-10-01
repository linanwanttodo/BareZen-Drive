package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GET and HEAD on a mount.
 *
 * Both are the same request as far as the client is concerned - HEAD is GET
 * without the body - so the properties pinned here are mostly about what is NOT
 * done: a rejected Range, a stale `If-Match` and a 304 must all be decided
 * before the blob is opened, because opening it costs a file descriptor that
 * only the GC would otherwise release. The recording storage below is what makes
 * that visible; a status-code assertion alone passes just as well for an
 * implementation that opens the blob and then throws the answer away.
 *
 * The rest is inherited rather than re-implemented: this route must go through
 * the shared stored-file responder, so the tests check that the content-type
 * policy, the pruned-blob 404 and the range handling a browser already gets
 * from `/api/files/{id}/content` are the same answers here. A second copy of
 * that logic is exactly the drift that produced
 * `StoredFileResponse.kt` in the first place.
 */
class DavReadTest {
    private val storageDir = java.nio.file.Files.createTempDirectory("bz-davread").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""
    private var token = ""
    private var readOnlyToken = ""
    private lateinit var storage: RecordingStorage

    /**
     * Counts what the route asks of the storage provider.
     *
     * `gets` is the one that matters: it is the call that opens the blob, and the
     * route owes the provider a matching `cancel`. `exists` is counted separately
     * so a test can say "the row was checked but the bytes were not touched".
     */
    private class RecordingStorage(private val inner: StorageProvider) : StorageProvider {
        val gets = AtomicInteger()
        val exists = AtomicInteger()

        override fun blobKey(sha256: String): String = inner.blobKey(sha256)
        override suspend fun put(key: String, channel: ByteReadChannel) = inner.put(key, channel)
        override suspend fun get(key: String): ByteReadChannel {
            gets.incrementAndGet()
            return inner.get(key)
        }

        override suspend fun delete(key: String) = inner.delete(key)
        override suspend fun exists(key: String): Boolean {
            exists.incrementAndGet()
            return inner.exists(key)
        }

        override fun resolvePath(key: String): Path? = inner.resolvePath(key)
        override val tmpDir: Path get() = inner.tmpDir
    }

    private fun basic(user: String, pass: String) =
        "Basic " + "$user:$pass".toByteArray().encodeBase64()

    /**
     * RFC 1123 in GMT, deliberately not the formatter the server uses: a test that
     * formats with the same code it is testing cannot catch a bad pattern.
     *
     * The day is padded explicitly. `java.time`'s RFC_1123_DATE_TIME leaves it
     * unpadded for a single-digit day, and a receiver that expects two digits
     * then treats the whole date as unparseable - which is a real interop trap,
     * not a test artefact.
     */
    private fun httpDate(millis: Long): String =
        java.time.format.DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.ofEpochMilli(millis))

    private suspend fun ApplicationTestBuilder.setup() {
        val cfg = AppConfig(
            0,
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
            registrationOpen = true,
        )
        storage = RecordingStorage(LocalStorageProvider(Path.of(cfg.storageDir)))
        application { module(cfg, storage) }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""")
            .find(client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"user1","password":"password123"}""")
            }.bodyAsText())!!.groupValues[1]
        token = mint(readOnly = false)
        readOnlyToken = mint(readOnly = true)
    }

    private suspend fun ApplicationTestBuilder.mint(readOnly: Boolean): String =
        (json.parseToJsonElement(
            client.post("/api/webdav/tokens") {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody("""{"label":"mount","readOnly":$readOnly}""")
            }.bodyAsText(),
        ) as JsonObject)["plaintext"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.mkFolder(name: String): String {
        val r = client.post("/api/folders") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":${json.encodeToString(name)}}""")
        }
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return Regex(""""id":"([^"]+)"""").find(r.bodyAsText())!!.groupValues[1]
    }

    /** Uploads through the ordinary API, so the row the mount reads is a real one. */
    private suspend fun ApplicationTestBuilder.upload(
        name: String,
        body: String,
        mimeType: String? = null,
        folder: String? = null,
        overwrite: Boolean = false,
    ): String {
        val bytes = body.encodeToByteArray()
        val init = """{"name":${json.encodeToString(name)},"size":${bytes.size}""" +
            (mimeType?.let { ""","mimeType":"$it"""" } ?: "") +
            (folder?.let { ""","folderId":"$it"""" } ?: "") +
            (if (overwrite) ""","overwrite":true""" else "") + "}"
        val ir = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody(init)
        }
        assertEquals(HttpStatusCode.OK, ir.status, ir.bodyAsText())
        val uploadId = (json.parseToJsonElement(ir.bodyAsText()) as JsonObject)["uploadId"]!!
            .jsonPrimitive.content
        val chunk = client.put("/api/uploads/$uploadId/chunks/0") {
            header(HttpHeaders.Authorization, auth)
            setBody(bytes)
        }
        assertEquals(HttpStatusCode.NoContent, chunk.status, chunk.bodyAsText())
        val done = client.post("/api/uploads/$uploadId/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return (json.parseToJsonElement(done.bodyAsText()) as JsonObject)["file"]!!
            .let { (it as JsonObject)["id"]!!.jsonPrimitive.content }
    }

    private suspend fun ApplicationTestBuilder.davGet(
        path: String,
        with: String = token,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = client.get(path) {
        header(HttpHeaders.Authorization, basic("user1", with))
        block()
    }

    private suspend fun ApplicationTestBuilder.davHead(
        path: String,
        with: String = token,
        block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = client.head(path) {
        header(HttpHeaders.Authorization, basic("user1", with))
        block()
    }

    private fun storageKeyOf(fileId: String): String = transaction(DatabaseFactory.db) {
        FilesTable.selectAll().where { FilesTable.id eq UUID.fromString(fileId) }.single()[FilesTable.storageKey]
    }

    private fun updatedAtOf(fileId: String): Long = transaction(DatabaseFactory.db) {
        FilesTable.selectAll().where { FilesTable.id eq UUID.fromString(fileId) }.single()[FilesTable.updatedAt]
    }

    // ---- the whole blob -------------------------------------------------------

    @Test
    fun getServesTheWholeBlobAndAdvertisesRangeSupport() = testApplication {
        setup()
        val content = "0123456789".repeat(10)
        upload("a.txt", content)

        val r = davGet("/dav/a.txt")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(content, r.bodyAsText())
        assertEquals("100", r.headers[HttpHeaders.ContentLength])
        // A mount is browsed by players that seek, so range support has to be
        // advertised or the client never tries.
        assertEquals("bytes", r.headers[HttpHeaders.AcceptRanges])
        assertTrue(r.headers[HttpHeaders.ETag] != null, "a read must be revalidatable")
        assertTrue(r.headers[HttpHeaders.LastModified] != null, "If-Modified-Since needs a Last-Modified")
    }

    @Test
    fun theEtagIsTheContentDigestSoItCannotLeakTheStorageLayout() = testApplication {
        setup()
        val id = upload("a.txt", "alpha")

        val etag = davGet("/dav/a.txt").headers[HttpHeaders.ETag]!!
        assertFalse(etag.contains("blobs/"), "the etag must not carry the storage key: $etag")
        // And it is the same value PROPFIND hands out, or a client could not
        // feed getetag back into If-Match.
        assertTrue(etag == "\"${shaOf(id)}\"", "expected the quoted sha256, got $etag")
    }

    // ---- ranges --------------------------------------------------------------

    @Test
    fun aByteRangeGetsExactlyThatSlice() = testApplication {
        setup()
        val content = "0123456789".repeat(10)
        upload("a.txt", content)

        val r = davGet("/dav/a.txt") { header(HttpHeaders.Range, "bytes=0-9") }
        assertEquals(HttpStatusCode.PartialContent, r.status, r.bodyAsText())
        assertEquals(10, r.bodyAsBytes().size)
        assertEquals(content.substring(0, 10), r.bodyAsText())
        assertEquals("bytes 0-9/100", r.headers[HttpHeaders.ContentRange])
        assertEquals("10", r.headers[HttpHeaders.ContentLength])
    }

    @Test
    fun anUnsatisfiableRangeIsRefusedWithoutOpeningTheBlob() = testApplication {
        setup()
        upload("a.txt", "short")

        val r = davGet("/dav/a.txt") { header(HttpHeaders.Range, "bytes=999-1000") }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, r.status, r.bodyAsText())
        assertEquals(
            0, storage.gets.get(),
            "a rejected Range must be refused before the blob is opened, or every " +
                "416 from a seeking player parks a file descriptor until the GC runs",
        )
    }

    // ---- conditional requests ------------------------------------------------

    @Test
    fun aMatchingEtagGetsA304WithNoBody() = testApplication {
        setup()
        upload("a.txt", "etag me")
        val etag = davGet("/dav/a.txt").headers[HttpHeaders.ETag]!!
        // The read above legitimately opened the blob; what matters is that the
        // revalidation does not.
        val opened = storage.gets.get()

        val r = davGet("/dav/a.txt") { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, r.status, r.bodyAsText())
        assertEquals(0, r.bodyAsBytes().size, "304 must not carry a body")
        assertEquals(etag, r.headers[HttpHeaders.ETag], "304 has to repeat the validator it matched")
        assertEquals(
            opened, storage.gets.get(),
            "a 304 must be decided before the blob is opened",
        )
    }

    @Test
    fun aStaleIfMatchIsPreconditionFailedAndTouchesNothing() = testApplication {
        setup()
        upload("a.txt", "one")
        val stale = davGet("/dav/a.txt").headers[HttpHeaders.ETag]!!
        // Content moved on, so that validator no longer names this resource.
        upload("a.txt", "two", overwrite = true)
        val opened = storage.gets.get()
        val probed = storage.exists.get()

        val r = davGet("/dav/a.txt") { header(HttpHeaders.IfMatch, stale) }
        assertEquals(HttpStatusCode.PreconditionFailed, r.status, r.bodyAsText())
        assertEquals(
            opened, storage.gets.get(),
            "the refusal must come before the blob is opened, which is what makes a " +
                "refused write - the same gate guards PUT - a no-op rather than a partial one",
        )
        assertEquals(
            probed, storage.exists.get(),
            "even the pruned-blob probe must not run: 412 says nothing about the payload",
        )
        // And the stored bytes are what they were: the refusal changed nothing.
        assertEquals("two", davGet("/dav/a.txt").bodyAsText())
    }

    @Test
    fun aMatchingIfMatchIsServed() = testApplication {
        setup()
        upload("a.txt", "one")
        val etag = davGet("/dav/a.txt").headers[HttpHeaders.ETag]!!

        val r = davGet("/dav/a.txt") { header(HttpHeaders.IfMatch, etag) }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("one", r.bodyAsText())
    }

    @Test
    fun ifModifiedSinceAfterTheLastWriteGetsA304() = testApplication {
        setup()
        val id = upload("a.txt", "one")
        val written = updatedAtOf(id)

        val r = davGet("/dav/a.txt") {
            header(HttpHeaders.IfModifiedSince, httpDate(written + 60_000))
        }
        assertEquals(HttpStatusCode.NotModified, r.status, r.bodyAsText())
        assertEquals(0, r.bodyAsBytes().size)
        assertEquals(0, storage.gets.get())
    }

    @Test
    fun ifModifiedSinceBeforeTheLastWriteIsServed() = testApplication {
        setup()
        val id = upload("a.txt", "one")
        val written = updatedAtOf(id)

        val r = davGet("/dav/a.txt") {
            header(HttpHeaders.IfModifiedSince, httpDate(written - 60_000))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("one", r.bodyAsText())
    }

    @Test
    fun theLastModifiedWeEmitIsTheOneTheClientCanSendBack() = testApplication {
        setup()
        val id = upload("a.txt", "one")
        val emitted = davGet("/dav/a.txt").headers[HttpHeaders.LastModified]!!

        // The stored timestamp has millisecond resolution and an HTTP date has
        // seconds; if the comparison did not floor both sides, the client's own
        // Last-Modified would read as "modified" and every revalidation would
        // redownload the file.
        assertEquals(
            HttpStatusCode.NotModified,
            davGet("/dav/a.txt") { header(HttpHeaders.IfModifiedSince, emitted) }.status,
            "the emitted Last-Modified must be a usable If-Modified-Since",
        )
        assertEquals(
            HttpStatusCode.OK,
            davGet("/dav/a.txt") { header(HttpHeaders.IfModifiedSince, httpDate(updatedAtOf(id) - 60_000)) }.status,
        )
    }

    // ---- HEAD ----------------------------------------------------------------

    @Test
    fun headCarriesTheLengthWithoutABody() = testApplication {
        setup()
        upload("a.txt", "12345")

        val r = davHead("/dav/a.txt")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("5", r.headers[HttpHeaders.ContentLength], "HEAD must report what GET would send")
        assertEquals(0, r.bodyAsBytes().size, "HEAD must not carry a body")
        assertEquals("bytes", r.headers[HttpHeaders.AcceptRanges], "HEAD must report the same capabilities as GET")
        assertTrue(r.headers[HttpHeaders.ETag] != null, "HEAD must report the same validators as GET")
        assertEquals(
            0, storage.gets.get(),
            "a client that only wants the size must not pay for the bytes",
        )
    }

    // ---- collections, misses, mounts ----------------------------------------

    @Test
    fun gettingACollectionReturnsAPlainHtmlIndex() = testApplication {
        setup()
        val folder = mkFolder("docs")
        upload("a.txt", "alpha", folder = folder)
        // No "/" in it - initUpload refuses a name containing one - but every
        // HTML metacharacter is here, which is the point.
        upload("<b>&x.txt", "beta", folder = folder)

        val r = davGet("/dav/docs")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(
            ContentType.Text.Html.contentType, r.contentType()?.contentType,
            "a collection GET is a browsable page, not a blob",
        )
        val body = r.bodyAsText()
        assertTrue(body.contains("a.txt"), body)
        // A file name is attacker-controlled text and this is an HTML page served
        // from the app's own origin, so it has to be escaped or an upload named
        // `<img onerror=...>` is script execution on the drive.
        assertTrue(body.contains("&lt;b&gt;&amp;x.txt"), "the name must be HTML-escaped: $body")
        assertFalse(body.contains("<b>&x.txt"), "a raw file name must never reach the page: $body")
    }

    @Test
    fun aPathThatDoesNotExistIsNotFound() = testApplication {
        setup()
        assertEquals(HttpStatusCode.NotFound, davGet("/dav/not-a-folder").status)
        assertEquals(HttpStatusCode.NotFound, davGet("/dav/nope.txt").status)
        assertEquals(HttpStatusCode.NotFound, davGet("/dav/missing/deep.txt").status)
    }

    @Test
    fun aReadOnlyMountCanStillRead() = testApplication {
        setup()
        upload("a.txt", "readable")

        val r = davGet("/dav/a.txt", with = readOnlyToken)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("readable", r.bodyAsText())
    }

    @Test
    fun anotherAccountsFileIsNotFound() = testApplication {
        setup()
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user2","password":"password123"}""")
        }
        val theirs = "Bearer " + Regex(""""accessToken":"([^"]+)"""")
            .find(client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"user2","password":"password123"}""")
            }.bodyAsText())!!.groupValues[1]
        val created = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, theirs)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"private.txt","size":7,"sha256":"${"a".repeat(64)}"}""")
        }
        val uploadId = (json.parseToJsonElement(created.bodyAsText()) as JsonObject)["uploadId"]!!
            .jsonPrimitive.content
        client.put("/api/uploads/$uploadId/chunks/0") {
            header(HttpHeaders.Authorization, theirs)
            setBody("private".encodeToByteArray())
        }
        client.post("/api/uploads/$uploadId/complete") { header(HttpHeaders.Authorization, theirs) }

        // 404 rather than 403: a 403 would confirm the name exists.
        assertEquals(HttpStatusCode.NotFound, davGet("/dav/private.txt").status)
    }

    // ---- inherited from the shared responder ---------------------------------

    @Test
    fun aMountServesAScriptableFileAsADownloadNotAsAPage() = testApplication {
        setup()
        val html = "<script>fetch('/api/me')</script>".encodeToByteArray()
        upload("evil.html", html.decodeToString(), mimeType = "text/html")

        val r = davGet("/dav/evil.html")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(
            ContentType.Application.OctetStream, r.contentType()?.withoutParameters(),
            "a stored html must not come back as html from the drive's own origin",
        )
        assertTrue(
            r.headers[HttpHeaders.ContentDisposition]?.startsWith("attachment", ignoreCase = true) == true,
            "it has to be a download: ${r.headers[HttpHeaders.ContentDisposition]}",
        )
        assertEquals(html.decodeToString(), r.bodyAsText(), "the bytes must still be the file's own")
    }

    @Test
    fun aBlobThatNoLongerExistsIsNotFoundRatherThanAFailedStream() = testApplication {
        setup()
        val id = upload("gone.txt", "will be pruned")
        java.nio.file.Files.delete(Path.of(storageDir).resolve(storageKeyOf(id)))

        val r = davGet("/dav/gone.txt")
        assertEquals(HttpStatusCode.NotFound, r.status, r.bodyAsText())
        assertEquals(0, storage.gets.get(), "a missing payload must not be opened either")
    }

    private fun shaOf(fileId: String): String = transaction(DatabaseFactory.db) {
        FilesTable.selectAll().where { FilesTable.id eq UUID.fromString(fileId) }.single()[FilesTable.sha256]
    }
}