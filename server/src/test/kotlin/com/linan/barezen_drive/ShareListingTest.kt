package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.FolderDto
import com.linan.barezen_drive.core.dto.ShareDto
import com.linan.barezen_drive.core.dto.SharesResponse
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.ShareLinksTable
import com.linan.barezen_drive.files.activeShareQuery
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/**
 * `listShares` used to read every share row the account owns into memory and
 * filter it in Kotlin: revoked/expired were dropped after the read, and the
 * target filter compared `UUID.toString()` against the raw query parameter. The
 * database saw a bare `user_id = ?` and sorted nothing, so the management
 * screen was a full scan plus an in-memory sort that grew with the account's
 * share history.
 *
 * The predicates belong in the WHERE clause. Two things follow from that, and
 * both are pinned here: the emitted SQL carries them (a shape assertion, since
 * "same rows, different plan" is otherwise invisible), and a malformed target
 * id becomes a 400 instead of silently matching nothing.
 */
class ShareListingTest {
    private val storageDir = Files.createTempDirectory("bz-sharelist").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""

    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "",
        "test-secret-0123456789abcdef0123456789abcdef",
        storageDir, 1L shl 30,
        // Registration is closed by default on a fresh instance; these tests
        // are not about that, so they open it explicitly.
        registrationOpen = true,
    )

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
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

    private suspend fun ApplicationTestBuilder.upload(body: ByteArray, name: String, folderId: String? = null): String {
        val folderField = folderId?.let { ""","folderId":"$it"""" } ?: ""
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"sha256":"${sha256hex(body)}"$folderField}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        val put = client.put("/api/uploads/${ir.uploadId}/chunks/0") {
            header(HttpHeaders.Authorization, auth)
            setBody(body)
        }
        assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
        val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
    }

    private suspend fun ApplicationTestBuilder.createFolder(name: String, parentId: String? = null): String {
        val parentField = parentId?.let { """"parentId":"$it",""" } ?: ""
        val res = client.post("/api/folders") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{$parentField"name":"$name"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        return json.decodeFromString<FolderDto>(res.bodyAsText()).id
    }

    private suspend fun ApplicationTestBuilder.shares(query: String = ""): List<ShareDto> {
        val res = client.get("/api/shares$query") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return json.decodeFromString<SharesResponse>(res.bodyAsText()).shares
    }

    // ---- SQL shape ---------------------------------------------------------

    @Test
    fun activeSharePredicatesArePushedIntoTheWhereClause() = testApplication {
        setup()
        val user = UUID.randomUUID()
        val target = UUID.randomUUID()
        val now = 1_700_000_000_000L
        // On an IO thread, and against the application's own connection:
        // DatabaseFactory is a process-wide singleton, and opening a transaction
        // on the JUnit thread leaves the next test's connect() building its
        // schema against the previous one.
        fun sql(fileId: UUID?) = runBlocking(Dispatchers.IO) {
            transaction(DatabaseFactory.db) { activeShareQuery(user, now, fileId, null).prepareSQL(this) }
        }
        val all = sql(null)
        val byFile = sql(target)
        fun flat(sql: String) = sql.lowercase().replace(Regex("\\s+"), " ").replace("\"", "")

        val flatAll = flat(all)
        assertTrue(
            flatAll.contains("revoked_at is null"),
            "revoked rows must be filtered by the database. SQL: $all",
        )
        assertTrue(
            flatAll.contains("expires_at is null") && flatAll.contains("expires_at >"),
            "expiry must be filtered by the database, in SQL. SQL: $all",
        )
        assertTrue(
            flatAll.contains("order by"),
            "the newest share first is also the database's job. SQL: $all",
        )

        val flatFile = flat(byFile)
        assertTrue(
            flatFile.contains("file_id ="),
            "the target filter must reach the WHERE clause so share_target_idx can serve it. SQL: $byFile",
        )
    }

    // ---- behaviour ---------------------------------------------------------

    @Test
    fun listHidesRevokedAndExpiredAndOrdersNewestFirst() = testApplication {
        setup()
        val a = upload("a".encodeToByteArray(), "la.txt")
        val b = upload("b".encodeToByteArray(), "lb.txt")
        val c = upload("c".encodeToByteArray(), "lc.txt")
        val created = listOf(a, b, c).map { id ->
            val res = client.post("/api/shares") {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody("""{"fileId":"$id"}""")
            }
            assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
            json.decodeFromString<ShareDto>(res.bodyAsText())
        }
        // Age the rows so the ordering is deterministic.
        transaction(DatabaseFactory.db) {
            created.forEachIndexed { i, dto ->
                ShareLinksTable.update({ ShareLinksTable.id eq UUID.fromString(dto.id) }) {
                    it[createdAt] = 1_000L + i * 10L
                }
            }
        }
        assertEquals(listOf("lc.txt", "lb.txt", "la.txt"), shares().map { it.targetName })

        // Revoke the middle one and expire the oldest.
        client.delete("/api/shares/${created[1].id}") { header(HttpHeaders.Authorization, auth) }
        transaction(DatabaseFactory.db) {
            ShareLinksTable.update({ ShareLinksTable.id eq UUID.fromString(created[0].id) }) {
                it[expiresAt] = 1L
            }
            ShareLinksTable.update({ ShareLinksTable.id eq UUID.fromString(created[2].id) }) {
                it[revokedAt] = 1L
            }
        }
        assertEquals(emptyList(), shares().map { it.targetName }, "expired and revoked rows stay hidden")
    }

    @Test
    fun targetFilterMatchesTheExactTargetOnly() = testApplication {
        setup()
        val a = upload("a".encodeToByteArray(), "ta.txt")
        val b = upload("b".encodeToByteArray(), "tb.txt")
        val folder = createFolder("target-folder")
        listOf("""{"fileId":"$a"}""", """{"fileId":"$b"}""", """{"folderId":"$folder"}""")
            .forEach {
                val res = client.post("/api/shares") {
                    header(HttpHeaders.Authorization, auth)
                    contentType(ContentType.Application.Json)
                    setBody(it)
                }
                assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
            }
        assertEquals(listOf("ta.txt"), shares("?fileId=$a").map { it.targetName })
        assertEquals(listOf("tb.txt"), shares("?fileId=$b").map { it.targetName })
        assertEquals(listOf("target-folder"), shares("?folderId=$folder").map { it.targetName })
        // A well-formed id that is not a share target of this account.
        assertEquals(emptyList(), shares("?fileId=${UUID.randomUUID()}").map { it.targetName })
    }

    @Test
    fun malformedTargetIdIsAValidationError() = testApplication {
        setup()
        val a = upload("a".encodeToByteArray(), "bad-id.txt")
        client.post("/api/shares") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"fileId":"$a"}""")
        }
        // The filter used to compare the raw query string against UUID.toString(),
        // so a typo answered "no shares" and looked like a lost link. Now that
        // the predicate is a real `file_id = ?` comparison, a value that cannot
        // be one is a client bug and says so.
        val res = client.get("/api/shares?fileId=not-a-uuid") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.BadRequest, res.status, res.bodyAsText())
        assertTrue(res.bodyAsText().contains("VALIDATION_ERROR"), res.bodyAsText())
    }

    @Test
    fun folderShareStillRejectsFilesOutsideTheSubtree() = testApplication {
        setup()
        val root = createFolder("shared")
        val inside = createFolder("inside", root)
        val outside = createFolder("outside")
        val insideFile = upload("in".encodeToByteArray(), "in.txt", inside)
        val outsideFile = upload("out".encodeToByteArray(), "out.txt", outside)
        val res = client.post("/api/shares") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"folderId":"$root"}""")
        }
        val token = json.decodeFromString<ShareDto>(res.bodyAsText()).url.removePrefix("/s/")
        assertEquals(HttpStatusCode.OK, client.get("/api/public/shares/$token/files/$insideFile/content").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/public/shares/$token/files/$outsideFile/content").status)
    }
}

/**
 * The subtree check used to walk the parent chain with one SELECT per level
 * and a 1000-level guard, so a folder tree deeper than that silently stopped
 * resolving - a public share of a deep archive answered 404 for files it does
 * contain. A recursive CTE answers the same question in one statement and has
 * no level cap; this walks a chain far past the old guard.
 */
class ShareSubtreeDepthTest {
    private val storageDir = Files.createTempDirectory("bz-subtree").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""
    private var storage = ""

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
        storage = storageDir
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        auth = "Bearer " + Regex("\"accessToken\":\"([^\"]+)\"").find(login.bodyAsText())!!.groupValues[1]
    }

    @Test
    fun subtreeCheckReachesBeyondTheOldThousandLevelGuard() = testApplication {
        setup()
        val user = transaction(DatabaseFactory.db) {
            com.linan.barezen_drive.db.UsersTable.selectAll().single()[com.linan.barezen_drive.db.UsersTable.id]
        }
        // A chain of 1500 folders, one level per iteration: deeper than the
        // 1000-step walk could follow.
        val chain = transaction(DatabaseFactory.db) {
            val ids = ArrayList<UUID>(1500)
            var parent: UUID? = null
            repeat(1500) { i ->
                val id = UUID.randomUUID()
                FoldersTable.insert {
                    it[FoldersTable.id] = id
                    it[FoldersTable.user] = user
                    it[FoldersTable.parent] = parent
                    it[FoldersTable.name] = "d$i"
                }
                ids += id
                parent = id
            }
            ids
        }
        val root = chain.first()
        val leaf = chain.last()
        // One file at the bottom of the chain, plus its blob.
        val sha = "b".repeat(64)
        val fileId = UUID.randomUUID()
        val bytes = "deep".encodeToByteArray()
        val blobKey = "blobs/bb/${"b".repeat(64)}"
        transaction(DatabaseFactory.db) {
            FilesTable.insert {
                it[FilesTable.id] = fileId
                it[FilesTable.user] = user
                it[FilesTable.folder] = leaf
                it[FilesTable.name] = "deep.txt"
                it[FilesTable.size] = bytes.size.toLong()
                it[FilesTable.mimeType] = "text/plain"
                it[FilesTable.sha256] = sha
                it[FilesTable.storageKey] = blobKey
            }
        }
        val blob = java.nio.file.Path.of(storage).resolve(blobKey)
        Files.createDirectories(blob.parent)
        Files.write(blob, bytes)

        val created = client.post("/api/shares") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"folderId":"$root"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val token = json.decodeFromString<ShareDto>(created.bodyAsText()).url.removePrefix("/s/")

        val res = client.get("/api/public/shares/$token/files/$fileId/content")
        assertEquals(HttpStatusCode.OK, res.status, "a 1500-deep subtree must still resolve: ${res.bodyAsText()}")
        assertEquals("deep", res.bodyAsText())
    }
}
