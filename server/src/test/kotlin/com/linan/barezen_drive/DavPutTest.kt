package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.BlobDeleteQueueTable
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FileVersionsTable
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PUT on a mount.
 *
 * The properties worth testing are not the status codes but what a write does to
 * the *rest* of the server. A mount overwrite must not grow the version table -
 * a Windows client re-PUTs a file it has just read, and a version per sync turns
 * the drive into an archive nobody asked for - and the blob the old content lived
 * in must reach the delete queue rather than becoming an unreferenced orphan.
 * Both are asserted against the database; a 2xx proves neither.
 *
 * The refusals are asserted with the same intent: a stale `If-Match` must be
 * decided before the storage provider is touched at all. The recording provider
 * makes that visible, where a status-code assertion passes just as well for an
 * implementation that wrote the body and then threw the answer away.
 */
class DavPutTest {
    private val storageDir = Files.createTempDirectory("bz-davput").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""
    private var token = ""
    private var readOnlyToken = ""
    private lateinit var storage: RecordingStorage

    /**
     * Counts what the route asks of the storage provider.
     *
     * `exists` is the one a refused write must not reach: it is the probe the
     * dedup and merge paths make, and a PUT that got that far has already
     * written something.
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

    private suspend fun ApplicationTestBuilder.setup(maxFileSize: Long = 1L shl 30) {
        val cfg = AppConfig(
            0,
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, maxFileSize,
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
        token = mint(auth, readOnly = false)
        readOnlyToken = mint(auth, readOnly = true)
    }

    private suspend fun ApplicationTestBuilder.mint(bearer: String, readOnly: Boolean): String =
        (json.parseToJsonElement(
            client.post("/api/webdav/tokens") {
                header(HttpHeaders.Authorization, bearer)
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

    private suspend fun ApplicationTestBuilder.put(
        path: String,
        body: String,
        with: String = token,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = client.put(path) {
        header(HttpHeaders.Authorization, basic("user1", with))
        block()
        setBody(body.encodeToByteArray())
    }

    /**
     * PUT whose length the client does not declare (chunked transfer encoding).
     *
     * `contentLength = null` is what makes Ktor emit `Transfer-Encoding:
     * chunked`; a body that knows its own length gets a Content-Length instead.
     * Without this shape a route could silently publish zero-byte files and no
     * declared-length test would notice.
     */
    private suspend fun ApplicationTestBuilder.putChunked(path: String, body: String): HttpResponse =
        client.put(path) {
            header(HttpHeaders.Authorization, basic("user1", token))
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType: ContentType = ContentType.Text.Plain
                    override val contentLength: Long? = null
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeFully(body.encodeToByteArray())
                        channel.flush()
                    }
                },
            )
        }

    /** PUT that announces [declared] bytes but delivers fewer: a dropped client. */
    private suspend fun ApplicationTestBuilder.putTruncated(
        path: String,
        declared: Long,
        delivered: String,
    ): HttpResponse = client.put(path) {
        header(HttpHeaders.Authorization, basic("user1", token))
        setBody(
            object : OutgoingContent.WriteChannelContent() {
                override val contentType: ContentType = ContentType.Application.OctetStream
                override val contentLength: Long? = declared
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeFully(delivered.encodeToByteArray())
                    channel.flush()
                }
            },
        )
    }

    /** init -> chunk -> complete through the ordinary API; the published id. */
    private suspend fun ApplicationTestBuilder.uploadViaApi(
        bearer: String,
        name: String,
        body: String,
    ): String? {
        val bytes = body.encodeToByteArray()
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"name":${json.encodeToString(name)},"size":${bytes.size}}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val uploadId = (json.parseToJsonElement(init.bodyAsText()) as JsonObject)["uploadId"]!!
            .jsonPrimitive.content
        client.put("/api/uploads/$uploadId/chunks/0") {
            header(HttpHeaders.Authorization, bearer)
            setBody(bytes)
        }
        val done = client.post("/api/uploads/$uploadId/complete") {
            header(HttpHeaders.Authorization, bearer)
        }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return (json.parseToJsonElement(done.bodyAsText()) as JsonObject)["file"]!!
            .let { (it as JsonObject)["id"]!!.jsonPrimitive.content }
    }

    /**
     * GET on a mount.
     *
     * The Basic username has to be the account the token belongs to: the
     * provider authenticates the pair, so another account's token under
     * "user1" is simply a wrong password (and answers 401, not 404).
     */
    private suspend fun ApplicationTestBuilder.davGet(
        path: String,
        with: String = token,
        user: String = "user1",
    ): HttpResponse = client.get(path) { header(HttpHeaders.Authorization, basic(user, with)) }

    private fun rowOf(name: String, user: String? = null) = transaction(DatabaseFactory.db) {
        val owner = user ?: return@transaction null
        FilesTable.selectAll()
            .where { (FilesTable.name eq name) and (FilesTable.user eq UUID.fromString(owner)) }
            .firstOrNull()
    }

    private fun myUserId(): String = transaction(DatabaseFactory.db) {
        com.linan.barezen_drive.db.UsersTable.selectAll()
            .where { com.linan.barezen_drive.db.UsersTable.username eq "user1" }
            .single()[com.linan.barezen_drive.db.UsersTable.id].toString()
    }

    private fun versionsOf(fileId: UUID): Long = transaction(DatabaseFactory.db) {
        FileVersionsTable.selectAll().where { FileVersionsTable.file eq fileId }.count()
    }

    private fun queuedKeys(): List<String> = transaction(DatabaseFactory.db) {
        BlobDeleteQueueTable.selectAll().map { it[BlobDeleteQueueTable.storageKey] }
    }

    private fun openSessions(): List<String> = transaction(DatabaseFactory.db) {
        UploadSessionsTable.selectAll()
            .where { UploadSessionsTable.status eq "open" }
            .map { it[UploadSessionsTable.id].toString() }
    }

    private fun stagedEntries(): Long =
        Files.list(Path.of(storageDir).resolve("tmp")).use { it.count() }

    // ---- happy path ----------------------------------------------------------

    @Test
    fun puttingAFileMakesItReadableThroughDav() = testApplication {
        setup()
        // A mount addresses a collection by its name, not by its id, so the path
        // uses "docs" - which is also the property that makes a rename visible
        // to the client as a move.
        mkFolder("docs")

        val r = put("/dav/docs/new.txt", "hello dav")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        assertEquals("hello dav", davGet("/dav/docs/new.txt").bodyAsText())
    }

    @Test
    fun aPutWithNoContentLengthIsAcceptedAndStoredAtItsMeasuredSize() = testApplication {
        setup()
        // Chunked transfer encoding: the route cannot know the size up front, so
        // this is the one shape that would silently produce a zero-byte file if
        // the measure-then-split fallback were missing.
        val body = "measured by the route"
        val r = putChunked("/dav/nolen.txt", body)
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        assertEquals(body, davGet("/dav/nolen.txt").bodyAsText())
        assertEquals(body.length.toLong(), rowOf("nolen.txt", myUserId())!![FilesTable.size])
    }

    @Test
    fun anEmptyBodyCreatesAnEmptyFile() = testApplication {
        setup()
        // A mount creates a file before its first save all the time (Word's
        // "new document", a text editor's open-and-save), and zero chunks means
        // `complete` merges nothing - the path where an off-by-one in the chunk
        // count shows up as a 400 instead of a 0-byte file.
        val r = put("/dav/empty.txt", "")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        val got = davGet("/dav/empty.txt")
        assertEquals(HttpStatusCode.OK, got.status, got.bodyAsText())
        assertEquals(0, got.bodyAsBytes().size, "the file must exist and be empty, not missing")
        assertEquals(0L, rowOf("empty.txt", myUserId())!![FilesTable.size])
    }

    @Test
    fun aBodyLargerThanThePipeBufferIsStreamedIntact() = testApplication {
        setup()
        // Well past the 1 MiB flush window the streaming pipe backpressures at,
        // so this is the case where a producer/consumer mismatch would truncate
        // the file or deadlock. Deterministic content, so a wrong splice shows up
        // as a byte difference rather than only a wrong length.
        val body = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val r = client.put("/dav/bulk.bin") {
            header(HttpHeaders.Authorization, basic("user1", token))
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        assertTrue(
            contentEquals("bulk.bin", body),
            "the stored bytes must equal the uploaded ones",
        )
        assertEquals(body.size.toLong(), rowOf("bulk.bin", myUserId())!![FilesTable.size])
        assertEquals(0L, stagedEntries(), "a multi-buffer PUT must still clean up after itself")
    }

    /** The stored bytes of [name], read through the content route. */
    private suspend fun ApplicationTestBuilder.contentEquals(name: String, expected: ByteArray): Boolean {
        val r = client.get("/api/files/${rowOf(name, myUserId())!![FilesTable.id]}/content") {
            header(HttpHeaders.Authorization, auth)
        }
        if (r.status != HttpStatusCode.OK) return false
        val got = r.bodyAsBytes()
        return got.size == expected.size && got.indices.all { got[it] == expected[it] }
    }

    @Test
    fun theStoredContentTypeIsTheClientsOwnWithoutItsParameters() = testApplication {
        setup()
        // What we store is what a later GET reports, and the parameters are about
        // this request's framing rather than the file - storing "text/plain;
        // charset=utf-8" would leak a client's charset choice into every reader.
        val r = client.put("/dav/typed.txt") {
            header(HttpHeaders.Authorization, basic("user1", token))
            header(HttpHeaders.ContentType, "text/plain; charset=utf-8")
            setBody("typed".encodeToByteArray())
        }
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        assertEquals("text/plain", rowOf("typed.txt", myUserId())!![FilesTable.mimeType])

        // And a type we have no better answer for stays unrecorded rather than
        // inventing one from the generic fallback a mount sends.
        val generic = client.put("/dav/untyped.bin") {
            header(HttpHeaders.Authorization, basic("user1", token))
            header(HttpHeaders.ContentType, "application/octet-stream")
            setBody("blob".encodeToByteArray())
        }
        assertEquals(HttpStatusCode.Created, generic.status, generic.bodyAsText())
        assertNull(rowOf("untyped.bin", myUserId())!![FilesTable.mimeType])
    }

    @Test
    fun puttingIdenticalBytesTwiceStillAnswersNoContentAndQueuesNothing() = testApplication {
        setup()
        assertEquals(HttpStatusCode.Created, put("/dav/same.txt", "payload").status)

        val again = put("/dav/same.txt", "payload")
        assertEquals(HttpStatusCode.NoContent, again.status, again.bodyAsText())
        assertEquals("payload", davGet("/dav/same.txt").bodyAsText())
        // Re-PUTting identical bytes is what a sync client does constantly; the
        // blob is still live, so it must not reach the delete queue.
        assertEquals(emptyList(), queuedKeys())
    }

    @Test
    fun aSuccessfulPutLeavesNoTemporaryFilesAround() = testApplication {
        setup()
        assertEquals(HttpStatusCode.Created, put("/dav/clean.txt", "payload").status)
        assertEquals(
            0L, stagedEntries(),
            "a successful PUT must clean its own staging directory",
        )
        assertEquals(emptyList(), openSessions())
    }

    // ---- overwrite semantics -------------------------------------------------

    @Test
    fun overwritingDoesNotCreateAVersionAndQueuesTheOldBlob() = testApplication {
        setup()
        assertEquals(HttpStatusCode.Created, put("/dav/docs.txt", "first").status)
        val row = rowOf("docs.txt", myUserId())!!
        val fileId = row[FilesTable.id]
        val oldKey = row[FilesTable.storageKey]

        val again = put("/dav/docs.txt", "second")
        assertEquals(HttpStatusCode.NoContent, again.status, again.bodyAsText())

        assertEquals(0L, versionsOf(fileId), "a mount overwrite must not enter version history")
        val after = rowOf("docs.txt", myUserId())!!
        assertEquals(fileId, after[FilesTable.id], "the row identity must survive the overwrite")
        assertEquals("second", davGet("/dav/docs.txt").bodyAsText())
        // The replaced blob lost its last reference, so it must be queued rather
        // than leaked. This is the step that is easiest to forget and the most
        // expensive to notice.
        assertTrue(oldKey in queuedKeys(), "the replaced blob must be queued: ${queuedKeys()}")
    }

    @Test
    fun overwritingIntoAFolderKeepsThatFolderUntouched() = testApplication {
        setup()
        mkFolder("taken")
        val r = put("/dav/taken", "x")

        assertEquals(HttpStatusCode.MethodNotAllowed, r.status, r.bodyAsText())
        // A PUT must never turn a collection into a file: the folder is the
        // client's namespace, not a slot to overwrite.
        assertEquals(HttpStatusCode.OK, davGet("/dav/taken").status)
    }

    // ---- refusals ------------------------------------------------------------

    @Test
    fun aReadOnlyTokenCannotWrite() = testApplication {
        setup()
        val r = put("/dav/nope.txt", "x", with = readOnlyToken)
        assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
        assertNull(rowOf("nope.txt", myUserId()), "a refused write must not leave a row")
        assertEquals(emptyList(), openSessions())
    }

    @Test
    fun aMissingParentIsConflictAndAnExistingCollectionIsMethodNotAllowed() = testApplication {
        setup()
        val missing = put("/dav/no-such-dir/x.txt", "x")
        assertEquals(HttpStatusCode.Conflict, missing.status, missing.bodyAsText())
        assertNull(rowOf("x.txt", myUserId()))

        mkFolder("adir")
        val ontoCollection = put("/dav/adir", "x")
        assertEquals(HttpStatusCode.MethodNotAllowed, ontoCollection.status, ontoCollection.bodyAsText())
        assertEquals(emptyList(), openSessions(), "no session may exist for a refused PUT")
    }

    @Test
    fun aDeclaredLengthOverTheServerCapIsInsufficientStorageNotPayloadTooLarge() = testApplication {
        // 1 MiB cap, 4 MiB announced: refused at init, so nothing is ever staged.
        setup(maxFileSize = 1L shl 20)
        val r = put("/dav/big.bin", "x".repeat(4 * 1024 * 1024))

        assertEquals(HttpStatusCode.InsufficientStorage, r.status, r.bodyAsText())
        // WebDAV answers a refused write with a DAV precondition element rather
        // than the API's JSON envelope: 413 tells a mount client the body was
        // malformed, which is not what happened.
        assertTrue(r.bodyAsText().contains("quota-not-exceeded"), r.bodyAsText())
        assertNull(rowOf("big.bin", myUserId()))
        assertEquals(0L, stagedEntries())
    }

    @Test
    fun anUndeclaredBodyOverTheCapStopsWhileStreamingInsteadOfFillingTheDisk() = testApplication {
        setup(maxFileSize = 1L shl 20)
        // No length to check against, so the only thing standing between this
        // request and a full disk is the route's own stop while it measures.
        val r = putChunked("/dav/bigchunked.bin", "x".repeat(4 * 1024 * 1024))

        assertEquals(HttpStatusCode.InsufficientStorage, r.status, r.bodyAsText())
        assertNull(rowOf("bigchunked.bin", myUserId()))
        assertEquals(
            0L, stagedEntries(),
            "the measuring spool must be deleted, not left holding the body",
        )
    }

    @Test
    fun aStaleIfMatchIsPreconditionFailedAndTouchesNothing() = testApplication {
        setup()
        assertEquals(HttpStatusCode.Created, put("/dav/guarded.txt", "one").status)
        val stale = davGet("/dav/guarded.txt").headers[HttpHeaders.ETag]!!
        // Content moves on, so that validator no longer names this resource.
        assertEquals(HttpStatusCode.NoContent, put("/dav/guarded.txt", "two").status)
        val probed = storage.exists.get()
        val opened = storage.gets.get()

        val r = put("/dav/guarded.txt", "three") { header(HttpHeaders.IfMatch, stale) }
        assertEquals(HttpStatusCode.PreconditionFailed, r.status, r.bodyAsText())
        assertEquals(
            probed, storage.exists.get(),
            "a refused PUT must be decided before the storage provider is asked anything",
        )
        assertEquals(opened, storage.gets.get())
        assertEquals("two", davGet("/dav/guarded.txt").bodyAsText(), "and it must change nothing")
        assertEquals(0L, versionsOf(rowOf("guarded.txt", myUserId())!![FilesTable.id]))
        assertEquals(emptyList(), openSessions(), "a 412 must not leave a staging session")
    }

    @Test
    fun aMatchingIfMatchWrites() = testApplication {
        setup()
        assertEquals(HttpStatusCode.Created, put("/dav/opt.txt", "one").status)
        val etag = davGet("/dav/opt.txt").headers[HttpHeaders.ETag]!!

        val r = put("/dav/opt.txt", "two") { header(HttpHeaders.IfMatch, etag) }
        assertEquals(HttpStatusCode.NoContent, r.status, r.bodyAsText())
        assertEquals("two", davGet("/dav/opt.txt").bodyAsText())
    }

    @Test
    fun ifMatchOnAFileThatDoesNotExistYetIsPreconditionFailed() = testApplication {
        setup()
        // `If-Match: *` means "only if it exists" - the optimistic-lock spelling
        // of "create". Answering 201 here would let two clients each believe
        // they created the file.
        val r = put("/dav/fresh.txt", "x") { header(HttpHeaders.IfMatch, "*") }
        assertEquals(HttpStatusCode.PreconditionFailed, r.status, r.bodyAsText())
        assertNull(rowOf("fresh.txt", myUserId()))
    }

    @Test
    fun aNameAnotherAccountHoldsIsUnrelatedToOurs() = testApplication {
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
        // No sha256 in the init: a declared hash that does not match the bytes makes
        // complete() refuse, which would leave nothing to compare against.
        val theirId = uploadViaApi(theirs, "private.txt", "private")
        assertNotNull(theirId, "the other account's upload must have published a row")
        val theirToken = mint(theirs, readOnly = false)
        val theirEtag = davGet("/dav/private.txt", with = theirToken, user = "user2").headers[HttpHeaders.ETag]
        assertNotNull(theirEtag, "the other account's file must be readable through its own mount")

        // Each account's root is its own namespace, so the same name here is a
        // *different* resource and creating it is a 201. What must not happen is
        // the write landing on their row - that would be a cross-account
        // overwrite, and it would be visible as their content changing.
        assertEquals(HttpStatusCode.Created, put("/dav/private.txt", "mine").status)
        assertEquals("mine", davGet("/dav/private.txt").bodyAsText())
        assertEquals("private", davGet("/dav/private.txt", with = theirToken, user = "user2").bodyAsText())
        assertEquals(
            theirEtag, davGet("/dav/private.txt", with = theirToken, user = "user2").headers[HttpHeaders.ETag],
            "the other account's file must be byte-identical after our PUT",
        )
    }

    // ---- staging cleanup -----------------------------------------------------

    @Test
    fun anInterruptedPutLeavesNoStagedParts() = testApplication {
        setup()
        // A dropped client announces more than it delivers: the merge would then
        // read a part file that was never finished. WebDAV has no abort verb, so
        // the route has to clean the staging directory itself.
        val r = putTruncated("/dav/short.bin", declared = 1L shl 20, delivered = "x".repeat(16))
        assertTrue(
            r.status == HttpStatusCode.BadRequest || r.status == HttpStatusCode.InsufficientStorage,
            "a truncated body must be refused, got ${r.status}",
        )
        assertNull(rowOf("short.bin", myUserId()), "nothing may be published from a refused PUT")
        assertEquals(emptyList(), openSessions(), "the staging session must not stay open")
        assertEquals(
            0L, stagedEntries(),
            "the staging directory must be removed, not merely left without rows",
        )
    }
}