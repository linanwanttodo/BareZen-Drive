package com.linan.barezen_drive

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.TrashResponse
import com.linan.barezen_drive.db.BlobDeleteQueueTable
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FileVersionsTable
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.files.FileService
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The structural write methods: MKCOL, MOVE, COPY, DELETE.
 *
 * A mount is used as a network drive, and dragging a folder in or around is the
 * most basic thing anybody does with one. So what matters here is not only the
 * status code but what the operation did to the rest of the server:
 *
 *  - **MOVE rewrites one row.** The assertion that pins this is that the
 *    grandchild file's `folder_id` and the grandchild folder's `parent_id` are
 *    *unchanged* after the move. An implementation that copied the subtree the
 *    way COPY does would produce different ids and fail here.
 *  - **COPY shares blobs.** Both file rows name one storage key, so a COPY that
 *    queued that key would have the purge sweep unlink bytes the source still
 *    reads. An empty `blob_delete_queue` is the assertion.
 *  - **DELETE of a file is recoverable.** It lands in the trash the app already
 *    has, and the test restores it rather than asserting the row vanished.
 *
 * Status codes are asserted too, but never as the only evidence: a bare
 * `assertEquals(404, ...)` goes green on a route that does not exist at all,
 * because the catch-all answers 404 for any unregistered method. Every test here
 * either asserts a status that is not 404 or asserts database state.
 */
class DavWriteStructureTest {
    private val storageDir = Files.createTempDirectory("bz-davwrite").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""
    private var token = ""
    private var readOnlyToken = ""

    private fun basic(user: String, pass: String) =
        "Basic " + "$user:$pass".toByteArray().encodeBase64()

    private suspend fun ApplicationTestBuilder.setup() {
        val cfg = AppConfig(
            0,
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
            registrationOpen = true,
        )
        application { module(cfg, LocalStorageProvider(Path.of(cfg.storageDir))) }
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

    /**
     * Folders and files are made through the ordinary API, so the setup does not
     * lean on the very methods under test: a fixture built with MKCOL would pass
     * even if MKCOL created the wrong shape of row.
     */
    private suspend fun ApplicationTestBuilder.makeFolder(name: String, parent: String? = null): String {
        val parentJson = if (parent == null) "" else ""","parentId":"$parent""""
        val r = client.post("/api/folders") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":${json.encodeToString(name)}$parentJson}""")
        }
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
        return Regex(""""id":"([^"]+)"""").find(r.bodyAsText())!!.groupValues[1]
    }

    /** init -> chunk -> complete through the ordinary API; the published id. */
    private suspend fun ApplicationTestBuilder.makeFile(
        name: String,
        body: String,
        folder: String? = null,
    ): String {
        val bytes = body.encodeToByteArray()
        val folderJson = if (folder == null) "" else ""","folderId":"$folder""""
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":${json.encodeToString(name)},"size":${bytes.size}$folderJson}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val uploadId = (json.parseToJsonElement(init.bodyAsText()) as JsonObject)["uploadId"]!!
            .jsonPrimitive.content
        client.put("/api/uploads/$uploadId/chunks/0") {
            header(HttpHeaders.Authorization, auth)
            setBody(bytes)
        }
        val done = client.post("/api/uploads/$uploadId/complete") {
            header(HttpHeaders.Authorization, auth)
        }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return (json.parseToJsonElement(done.bodyAsText()) as JsonObject)["file"]!!
            .let { (it as JsonObject)["id"]!!.jsonPrimitive.content }
    }

    private suspend fun ApplicationTestBuilder.davPut(path: String, body: String): HttpResponse =
        client.put(path) {
            header(HttpHeaders.Authorization, basic("user1", token))
            setBody(body.encodeToByteArray())
        }

    // ---- request helpers -----------------------------------------------------

    /**
     * A WebDAV request with a non-standard method.
     *
     * `HttpRequestBuilder.method` is a plain `var` - there is no `method(...)`
     * function on the builder - and `client.request` rather than a per-method
     * shortcut because Ktor has none for MKCOL/MOVE/COPY.
     */
    private suspend fun ApplicationTestBuilder.davReq(
        verb: HttpMethod,
        path: String,
        with: String = token,
        user: String = "user1",
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = client.request(path) {
        method = verb
        header(HttpHeaders.Authorization, basic(user, with))
        block()
    }

    private suspend fun ApplicationTestBuilder.mkcol(
        path: String,
        with: String = token,
        body: String? = null,
    ): HttpResponse = davReq(HttpMethod("MKCOL"), path, with) {
        if (body != null) setBody(body)
    }

    private suspend fun ApplicationTestBuilder.mov(
        path: String,
        destination: String?,
        with: String = token,
        overwrite: String? = null,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = davReq(HttpMethod("MOVE"), path, with) {
        destination?.let { header(HttpHeaders.Destination, it) }
        overwrite?.let { header(HttpHeaders.Overwrite, it) }
        block()
    }

    private suspend fun ApplicationTestBuilder.cp(
        path: String,
        destination: String?,
        with: String = token,
        overwrite: String? = null,
    ): HttpResponse = davReq(HttpMethod("COPY"), path, with) {
        destination?.let { header(HttpHeaders.Destination, it) }
        overwrite?.let { header(HttpHeaders.Overwrite, it) }
    }

    private suspend fun ApplicationTestBuilder.del(
        path: String,
        with: String = token,
        user: String = "user1",
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = davReq(HttpMethod("DELETE"), path, with, user, block)

    private suspend fun ApplicationTestBuilder.davGet(path: String, with: String = token): HttpResponse =
        client.get(path) { header(HttpHeaders.Authorization, basic("user1", with)) }

    /**
     * The same-origin absolute URL a mount sends.
     *
     * Hardcoded rather than derived from the request's own host: a test that
     * computed the expected Destination from whatever the server considers its
     * origin would agree with the server by construction, and could not catch a
     * comparison against the wrong thing.
     */
    private fun davUrl(path: String) = "http://localhost$path"

    // ---- database helpers ----------------------------------------------------

    private fun userId(name: String): UUID = transaction(DatabaseFactory.db) {
        UsersTable.selectAll().where { UsersTable.username eq name }.single()[UsersTable.id]
    }

    private fun folderRows(name: String, owner: UUID = userId("user1")): List<ResultRow> =
        transaction(DatabaseFactory.db) {
            FoldersTable.selectAll()
                .where { (FoldersTable.name eq name) and (FoldersTable.user eq owner) }
                .toList()
        }

    private fun folderRow(name: String, owner: UUID = userId("user1")): ResultRow? = folderRows(name, owner).firstOrNull()

    private fun fileRows(name: String, owner: UUID = userId("user1")): List<ResultRow> =
        transaction(DatabaseFactory.db) {
            FilesTable.selectAll()
                .where { (FilesTable.name eq name) and (FilesTable.user eq owner) }
                .toList()
        }

    private fun liveFileRow(name: String, owner: UUID = userId("user1")): ResultRow? =
        fileRows(name, owner).firstOrNull { it[FilesTable.deletedAt] == 0L }

    private fun queuedKeys(): List<String> = transaction(DatabaseFactory.db) {
        BlobDeleteQueueTable.selectAll().map { it[BlobDeleteQueueTable.storageKey] }
    }

    private fun versionsOf(fileId: UUID): Long = transaction(DatabaseFactory.db) {
        FileVersionsTable.selectAll().where { FileVersionsTable.file eq fileId }.count()
    }

    private suspend fun ApplicationTestBuilder.trashPage(): TrashResponse = json.decodeFromString(
        client.get("/api/trash") { header(HttpHeaders.Authorization, auth) }.bodyAsText(),
    )

    // ---- MKCOL ---------------------------------------------------------------

    @Test
    fun mkcolCreatesAFolderEvenWithANonAsciiName() = testApplication {
        setup()
        val r = mkcol("/dav/新文件夹")
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())

        // Not the status code alone: the row has to exist, at the account root,
        // under the name the URL spelled.
        val row = folderRow("新文件夹")
        assertNotNull(row, "MKCOL must have written a folders row")
        assertNull(row[FoldersTable.parent])
        // ... and it has to be addressable, which is what a mount is for. The
        // trailing slash is the canonical collection URL a client will ask for.
        assertEquals(HttpStatusCode.OK, davGet("/dav/新文件夹/").status)
    }

    @Test
    fun mkcolOnAnExistingResourceIsMethodNotAllowed() = testApplication {
        setup()
        makeFolder("taken")
        assertEquals(HttpStatusCode.MethodNotAllowed, mkcol("/dav/taken").status)

        // A file of the same name is the same answer: siblings share one
        // namespace, and MKCOL must not replace it with a collection.
        makeFile("clash.txt", "x")
        assertEquals(HttpStatusCode.MethodNotAllowed, mkcol("/dav/clash.txt").status)
        assertEquals(1, fileRows("clash.txt").size, "the existing file row must survive")
        assertNull(folderRow("clash.txt"))
    }

    @Test
    fun mkcolWithABodyIsUnsupportedMediaType() = testApplication {
        setup()
        // RFC 4918 §8.3.1: MKCOL as defined here takes no body, so a body is a
        // media type we do not handle. Silently ignoring it would let a client
        // believe an extended MKCOL had been applied.
        val r = mkcol("/dav/withbody", body = "<extended-mkcol/>")
        assertEquals(HttpStatusCode.UnsupportedMediaType, r.status, r.bodyAsText())
        assertNull(folderRow("withbody"), "a refused MKCOL must not leave a row")
    }

    @Test
    fun mkcolUnderAMissingParentIsConflict() = testApplication {
        setup()
        val missing = mkcol("/dav/no-such-dir/child")
        assertEquals(HttpStatusCode.Conflict, missing.status, missing.bodyAsText())
        assertNull(folderRow("child"))

        // A file used as a directory is the same answer: the path is wrong and
        // the client has to change it, not retry.
        makeFile("afile.txt", "x")
        val throughFile = mkcol("/dav/afile.txt/child")
        assertEquals(HttpStatusCode.Conflict, throughFile.status, throughFile.bodyAsText())
        assertEquals(1, fileRows("afile.txt").size)
    }

    @Test
    fun mkcolOnTheBareRootIsMethodNotAllowed() = testApplication {
        setup()
        // Both spellings are registered routes, so both have to be answered -
        // and neither may create anything.
        assertEquals(HttpStatusCode.MethodNotAllowed, mkcol("/dav/").status)
        assertEquals(HttpStatusCode.MethodNotAllowed, mkcol("/dav").status)
    }

    // ---- MOVE ----------------------------------------------------------------

    @Test
    fun moveTakesTheWholeSubtreeByRewritingOneRow() = testApplication {
        setup()
        val outer = makeFolder("outer")
        val inner = makeFolder("inner", parent = outer)
        val fileId = makeFile("deep.txt", "grandchild bytes", folder = inner)

        val r = mov("/dav/outer", davUrl("/dav/moved"))
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())

        // The grandchild came along, which is the whole point of the test.
        val deep = davGet("/dav/moved/inner/deep.txt")
        assertEquals(HttpStatusCode.OK, deep.status, deep.bodyAsText())
        assertEquals("grandchild bytes", deep.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, davGet("/dav/outer/inner/deep.txt").status)

        // One row was rewritten, not the subtree copied: the file still points at
        // the same inner folder, and that inner folder still points at outer.
        val movedRow = folderRow("moved")!!
        assertEquals(outer, movedRow[FoldersTable.id].toString(), "the folder row identity must survive")
        assertNull(movedRow[FoldersTable.parent])
        assertEquals(movedRow[FoldersTable.id], folderRow("inner")!![FoldersTable.parent])
        val fileRow = fileRows("deep.txt").single()
        assertEquals(fileId, fileRow[FilesTable.id].toString())
        assertEquals(inner, fileRow[FilesTable.folder].toString())
        assertEquals(
            emptyList(), queuedKeys(),
            "a MOVE rewrites a pointer; it must never unlink a blob either path still needs",
        )
    }

    @Test
    fun moveOfAFileKeepsItsRowAndItsBlob() = testApplication {
        setup()
        val into = makeFolder("into")
        val fileId = makeFile("f.txt", "payload")

        val r = mov("/dav/f.txt", davUrl("/dav/into/f.txt"))
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())

        val row = fileRows("f.txt").single()
        assertEquals(fileId, row[FilesTable.id].toString())
        assertEquals(into, row[FilesTable.folder].toString())
        assertEquals("payload", davGet("/dav/into/f.txt").bodyAsText())
        assertEquals(emptyList(), queuedKeys())
    }

    @Test
    fun moveIntoItsOwnSubtreeIsForbidden() = testApplication {
        setup()
        makeFolder("outer")
        makeFolder("inner", parent = folderRow("outer")!![FoldersTable.id].toString())

        val r = mov("/dav/outer", davUrl("/dav/outer/inner/moved"))
        assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
        assertNotNull(folderRow("outer"), "a refused MOVE must leave the source where it was")
        assertNotNull(folderRow("inner"))
        assertNull(folderRow("moved"))

        // Onto itself is a no-op, not a destroy-then-move of the same row.
        val ontoSelf = mov("/dav/outer", davUrl("/dav/outer"))
        assertEquals(HttpStatusCode.NoContent, ontoSelf.status, ontoSelf.bodyAsText())
        assertNotNull(folderRow("outer"))
    }

    @Test
    fun overwriteDecidesWhetherAnOccupiedDestinationIsReplaced() = testApplication {
        setup()
        val victim = makeFolder("victim")
        makeFile("v.txt", "original", folder = victim)
        val target = makeFolder("target")
        makeFile("t.txt", "target original", folder = target)

        val skipped = mov("/dav/victim", davUrl("/dav/target"), overwrite = "F")
        assertEquals(HttpStatusCode.NoContent, skipped.status, skipped.bodyAsText())
        assertEquals("target original", davGet("/dav/target/t.txt").bodyAsText(), "Overwrite: F must change nothing")
        assertNotNull(folderRow("victim"))
        assertNotNull(folderRow("target"))

        val replaced = mov("/dav/victim", davUrl("/dav/target"), overwrite = "T")
        assertEquals(HttpStatusCode.NoContent, replaced.status, replaced.bodyAsText())
        assertEquals("original", davGet("/dav/target/v.txt").bodyAsText(), "the moved subtree must be in place")
        assertNull(folderRow("victim"), "the source path must be gone")
        assertNull(fileRows("t.txt").firstOrNull(), "the replaced destination subtree is gone")
        // The row answering to "target" is now the moved subtree, not the row
        // that used to be there: a MOVE rewrites one row, so the source's
        // descendants (which point at it) keep working only if it is the source
        // row that survives under the new name.
        assertEquals(victim, folderRow("target")!![FoldersTable.id].toString(), "the moved row took the name")
        assertNotEquals(target, folderRow("target")!![FoldersTable.id].toString())
        // The replaced file's blob really did lose its last reference, so it is
        // queued - the opposite of what a COPY does.
        assertTrue(queuedKeys().isNotEmpty(), "the overwritten subtree's orphan blob must be queued")
    }

    // ---- COPY ----------------------------------------------------------------

    @Test
    fun copyDuplicatesTheSubtreeAndLeavesTheSourceAlone() = testApplication {
        setup()
        val src = makeFolder("src")
        val inner = makeFolder("inner", parent = src)
        val srcFile = makeFile("f.txt", "payload", folder = src)
        val deepFile = makeFile("deep.txt", "deeper payload", folder = inner)

        val r = cp("/dav/src", davUrl("/dav/dst"))
        assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())

        assertEquals("payload", davGet("/dav/src/f.txt").bodyAsText())
        assertEquals("payload", davGet("/dav/dst/f.txt").bodyAsText())
        assertEquals("deeper payload", davGet("/dav/dst/inner/deep.txt").bodyAsText())

        // New rows all the way down...
        val copiedRoot = folderRow("dst")!!
        assertNotEquals(src, copiedRoot[FoldersTable.id].toString())
        val inners = folderRows("inner")
        assertEquals(2, inners.size, "the nested folder was duplicated, not moved")
        assertEquals(
            setOf(src, copiedRoot[FoldersTable.id].toString()),
            inners.map { it[FoldersTable.parent].toString() }.toSet(),
        )
        val copies = fileRows("f.txt")
        assertEquals(2, copies.size, "a copy is a second row, not a move")
        assertEquals(1, copies.count { it[FilesTable.id].toString() == srcFile })
        assertEquals(
            1, copies.map { it[FilesTable.storageKey] }.toSet().size,
            "a copy shares the blob, so both rows must name the same storage key",
        )
        val deepCopies = fileRows("deep.txt")
        assertEquals(2, deepCopies.size)
        assertEquals(1, deepCopies.count { it[FilesTable.id].toString() == deepFile })
        assertEquals(
            1, deepCopies.map { it[FilesTable.storageKey] }.toSet().size,
            "the nested file's blob is shared the same way",
        )
    }

    @Test
    fun copyQueuesNoBlobForDeletionAndCreatesNoVersions() = testApplication {
        setup()
        val src = makeFolder("src")
        val inner = makeFolder("inner", parent = src)
        val srcFile = makeFile("f.txt", "payload", folder = src)
        val deepFile = makeFile("deep.txt", "deeper", folder = inner)

        assertEquals(HttpStatusCode.Created, cp("/dav/src", davUrl("/dav/dst")).status)
        assertEquals(
            emptyList(), queuedKeys(),
            "a COPY duplicates rows, not bytes: the shared blob must never reach the delete queue",
        )
        // A copy is a file whose only revision is the content it was born with;
        // a version row would keep a second reference nothing asked for.
        for (original in listOf(srcFile, deepFile)) {
            val name = if (original == srcFile) "f.txt" else "deep.txt"
            val copied = fileRows(name).first { it[FilesTable.id].toString() != original }
            assertEquals(0L, versionsOf(copied[FilesTable.id]), "$name copy must have no version history")
        }
        // ... and the source is still whole.
        assertEquals("payload", davGet("/dav/src/f.txt").bodyAsText())
        assertEquals("deeper", davGet("/dav/src/inner/deep.txt").bodyAsText())
    }

    @Test
    fun overwriteFalseSkipsAndOverwriteTrueReplacesTheDestination() = testApplication {
        setup()
        makeFile("dst.txt", "mine")
        makeFile("src.txt", "theirs")

        val skipped = cp("/dav/src.txt", davUrl("/dav/dst.txt"), overwrite = "F")
        assertEquals(HttpStatusCode.NoContent, skipped.status, skipped.bodyAsText())
        assertEquals("mine", davGet("/dav/dst.txt").bodyAsText(), "Overwrite: F must leave the target alone")
        assertEquals("theirs", davGet("/dav/src.txt").bodyAsText())

        val replaced = cp("/dav/src.txt", davUrl("/dav/dst.txt"), overwrite = "T")
        assertEquals(HttpStatusCode.NoContent, replaced.status, replaced.bodyAsText())
        assertEquals("theirs", davGet("/dav/dst.txt").bodyAsText())
        // The replaced row goes to the trash, which is where the app puts it too,
        // and which is exactly why its blob must NOT be queued.
        assertEquals(listOf("dst.txt"), trashPage().files.map { it.name })
        assertEquals(
            emptyList(), queuedKeys(),
            "the trashed row still references the blob it shared with the copy",
        )
        assertEquals("theirs", davGet("/dav/src.txt").bodyAsText(), "the source survives its own copy")
    }

    @Test
    fun copyOntoACollectionWithAFileIsMethodNotAllowed() = testApplication {
        setup()
        val dst = makeFolder("dst")
        makeFile("inside.txt", "x", folder = dst)
        makeFile("src.txt", "theirs")

        val r = cp("/dav/src.txt", davUrl("/dav/dst"))
        assertEquals(HttpStatusCode.MethodNotAllowed, r.status, r.bodyAsText())
        assertEquals(HttpStatusCode.OK, davGet("/dav/dst/inside.txt").status, "the collection must be untouched")
    }

    @Test
    fun copyOntoItselfIsForbidden() = testApplication {
        setup()
        makeFolder("src")
        val r = cp("/dav/src", davUrl("/dav/src"), overwrite = "T")
        assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
        assertNotNull(folderRow("src"))
        // The refusal must not have poisoned the next attempt.
        assertEquals(HttpStatusCode.Created, cp("/dav/src", davUrl("/dav/src2")).status)
        assertNotNull(folderRow("src2"))
    }

    // ---- Destination ---------------------------------------------------------

    @Test
    fun destinationMustBePresentAndSameOrigin() = testApplication {
        setup()
        makeFile("f.txt", "x")

        assertEquals(HttpStatusCode.BadRequest, mov("/dav/f.txt", null).status, "no Destination at all")
        assertEquals(HttpStatusCode.BadRequest, cp("/dav/f.txt", null).status, "no Destination at all")
        assertEquals(
            HttpStatusCode.BadRequest, mov("/dav/f.txt", "http://elsewhere.example/dav/x").status,
            "a cross-origin Destination names another server, not a resource here",
        )
        assertEquals(HttpStatusCode.BadRequest, cp("/dav/f.txt", "https://localhost.evil.example/dav/x").status)
        assertEquals(
            HttpStatusCode.BadRequest, mov("/dav/f.txt", davUrl("/api/files")).status,
            "a Destination outside /dav names no resource on this surface",
        )
        assertEquals(HttpStatusCode.BadRequest, mov("/dav/f.txt", "not a uri at all").status)
        assertEquals(HttpStatusCode.BadRequest, mov("/dav/f.txt", "").status)
        // None of that may have moved anything.
        assertNull(liveFileRow("f.txt")!![FilesTable.folder])

        // The RFC's other spelling: a bare absolute path is same-origin by
        // construction, and some clients send it.
        assertEquals(HttpStatusCode.Created, mov("/dav/f.txt", "/dav/renamed.txt").status)
        assertNotNull(liveFileRow("renamed.txt"))
    }

    @Test
    fun aDestinationParentThatDoesNotExistIsConflict() = testApplication {
        setup()
        makeFile("f.txt", "x")

        val missing = mov("/dav/f.txt", davUrl("/dav/nope/f.txt"))
        assertEquals(HttpStatusCode.Conflict, missing.status, missing.bodyAsText())
        assertNull(liveFileRow("f.txt")!![FilesTable.folder], "a refused MOVE must not have moved it")

        val copied = cp("/dav/f.txt", davUrl("/dav/nope/deeper/f.txt"))
        assertEquals(HttpStatusCode.Conflict, copied.status, copied.bodyAsText())
        assertEquals(1, fileRows("f.txt").size)

        // A file used as a directory is the same 409 PUT already answers.
        val throughFile = mov("/dav/f.txt", davUrl("/dav/f.txt/sub.txt"))
        assertEquals(HttpStatusCode.Conflict, throughFile.status, throughFile.bodyAsText())
        assertEquals(1, fileRows("f.txt").size)
    }

    // ---- DELETE --------------------------------------------------------------

    @Test
    fun deleteOfAFileIsRecoverableFromTheTrash() = testApplication {
        setup()
        val fileId = makeFile("gone.txt", "bye")

        val r = del("/dav/gone.txt")
        assertEquals(HttpStatusCode.NoContent, r.status, r.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, davGet("/dav/gone.txt").status)
        // The row survives, so the bytes must not be unlinked while it is there.
        assertEquals(emptyList(), queuedKeys())
        assertEquals(listOf(fileId), trashPage().files.map { it.id })

        // ... and it really is recoverable.
        val restored = client.post("/api/trash/$fileId/restore") {
            header(HttpHeaders.Authorization, auth)
        }
        assertEquals(HttpStatusCode.OK, restored.status, restored.bodyAsText())
        val back = davGet("/dav/gone.txt")
        assertEquals(HttpStatusCode.OK, back.status)
        assertEquals("bye", back.bodyAsText())
        assertEquals(0, trashPage().files.size)
    }

    @Test
    fun deleteOfACollectionTakesTheWholeSubtree() = testApplication {
        setup()
        val tree = makeFolder("tree")
        val inner = makeFolder("inner", parent = tree)
        makeFile("x.txt", "x", folder = tree)
        makeFile("deep.txt", "d", folder = inner)

        val r = del("/dav/tree")
        assertEquals(HttpStatusCode.NoContent, r.status, r.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, davGet("/dav/tree/x.txt").status)
        assertEquals(HttpStatusCode.NotFound, davGet("/dav/tree/inner/deep.txt").status)
        assertEquals(HttpStatusCode.NotFound, davGet("/dav/tree/").status)
        assertNull(folderRow("tree"))
        assertNull(folderRow("inner"))
        assertEquals(emptyList(), fileRows("x.txt"), "the file rows go with their folder")
        assertEquals(emptyList(), fileRows("deep.txt"))
        // A collection delete is the app's own folder delete, so nothing is left
        // for a restore to point at - and the two blobs really are unreferenced.
        assertEquals(0, trashPage().files.size)
        assertEquals(2, queuedKeys().size, "both orphaned blobs must be queued")
    }

    @Test
    fun deleteOfTheBareRootIsRefused() = testApplication {
        setup()
        makeFile("keep.txt", "x")
        assertEquals(HttpStatusCode.MethodNotAllowed, del("/dav/").status)
        assertEquals(HttpStatusCode.MethodNotAllowed, del("/dav").status)
        assertNotNull(liveFileRow("keep.txt"), "a root delete must not have emptied the drive")
        assertEquals(1, fileRows("keep.txt").size)
    }

    @Test
    fun deleteOfSomethingThatIsNotThereIsNotFound() = testApplication {
        setup()
        assertEquals(HttpStatusCode.NotFound, del("/dav/never-existed.txt").status)
        assertEquals(HttpStatusCode.NotFound, del("/dav/never/been/here").status)
    }

    // ---- conditionals --------------------------------------------------------

    @Test
    fun aStaleValidatorRefusesDeleteAndMoveBeforeAnythingIsTouched() = testApplication {
        setup()
        assertEquals(HttpStatusCode.Created, davPut("/dav/f.txt", "one").status)
        val stale = davGet("/dav/f.txt").headers[HttpHeaders.ETag]!!
        assertEquals(HttpStatusCode.NoContent, davPut("/dav/f.txt", "two").status)

        val d = del("/dav/f.txt") { header(HttpHeaders.IfMatch, stale) }
        assertEquals(HttpStatusCode.PreconditionFailed, d.status, d.bodyAsText())
        assertNotNull(liveFileRow("f.txt"), "a refused DELETE must not have trashed it")
        assertEquals(0, trashPage().files.size)

        val m = mov("/dav/f.txt", davUrl("/dav/renamed.txt")) { header(HttpHeaders.IfMatch, stale) }
        assertEquals(HttpStatusCode.PreconditionFailed, m.status, m.bodyAsText())
        assertNotNull(liveFileRow("f.txt"), "a refused MOVE must not have moved it")

        // ... and a fresh validator is accepted, so the refusal was the
        // precondition and not a permanently broken route.
        val fresh = davGet("/dav/f.txt").headers[HttpHeaders.ETag]!!
        assertEquals(HttpStatusCode.NoContent, del("/dav/f.txt") { header(HttpHeaders.IfMatch, fresh) }.status)
        assertEquals(1, trashPage().files.size)
    }

    // ---- read-only mounts ----------------------------------------------------

    @Test
    fun aReadOnlyTokenCannotChangeAnythingOnAnyOfTheFourMethods() = testApplication {
        setup()
        val folder = makeFolder("keep")
        makeFile("keep.txt", "x", folder = folder)

        // One loop over all four, because a method added to this surface without
        // also being added to the read-only refusal set would silently become
        // writable on a read-only mount. 403 is an answer the catch-all 404
        // cannot produce, so this fails if either the route or the set is wrong.
        val attempts = listOf(
            "MKCOL" to mkcol("/dav/nope", readOnlyToken),
            "DELETE" to del("/dav/keep/keep.txt", readOnlyToken),
            "MOVE" to mov("/dav/keep/keep.txt", davUrl("/dav/keep/renamed.txt"), readOnlyToken),
            "COPY" to cp("/dav/keep/keep.txt", davUrl("/dav/copied.txt"), readOnlyToken),
        )
        for ((name, r) in attempts) {
            assertEquals(
                HttpStatusCode.Forbidden, r.status,
                "$name must be refused on a read-only mount: ${r.bodyAsText()}",
            )
        }

        // Nothing moved: every one of those rows is where it was.
        assertNull(folderRow("nope"))
        assertNotNull(liveFileRow("keep.txt"))
        assertNull(liveFileRow("renamed.txt"))
        assertNull(liveFileRow("copied.txt"))
        assertEquals(emptyList(), trashPage().files)
        assertEquals(1, fileRows("keep.txt").size)
    }

    // ---- cross-account -------------------------------------------------------

    @Test
    fun anotherAccountsResourceIsOutOfReach() = testApplication {
        setup()
        val folder = makeFolder("mine")
        val fileId = makeFile("mine.txt", "mine", folder = folder)
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user2","password":"password123"}""")
        }
        val otherAuth = "Bearer " + Regex(""""accessToken":"([^"]+)"""")
            .find(client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"user2","password":"password123"}""")
            }.bodyAsText())!!.groupValues[1]
        val otherToken = mint(otherAuth, readOnly = false)
        val other = userId("user2")

        // At the route level a mount addresses resources by name inside its own
        // account, so the second account's path walk cannot even resolve this
        // account's row. That is what keeps the answer from confirming anything:
        // the request is simply answered about the caller's own namespace.
        assertEquals(HttpStatusCode.NotFound, del("/dav/mine.txt", otherToken, "user2").status)
        assertEquals(HttpStatusCode.NotFound, del("/dav/mine", otherToken, "user2").status)
        assertNotNull(liveFileRow("mine.txt"), "and it must change nothing")

        // The service layer is where an id could still arrive from another
        // account, and it refuses rather than quietly writing to a row it does
        // not own.
        val myFile = UUID.fromString(fileId)
        val myFolder = UUID.fromString(folder)
        val attempts = listOf<() -> Unit>(
            { FileService.moveTo(other, myFile, null, "stolen.txt") },
            { FileService.copySubtree(other, myFolder, null, "stolen") },
        )
        for (attempt in attempts) {
            val failure = runCatching(attempt).exceptionOrNull()
            assertNotNull(failure, "another account's row must be refused")
            assertEquals(HttpStatusCode.Forbidden, (failure as ApiException).status, failure.message)
        }
        assertEquals("mine.txt", liveFileRow("mine.txt")!![FilesTable.name], "and nothing of ours moved")
        assertNull(folderRow("stolen"))
    }
}