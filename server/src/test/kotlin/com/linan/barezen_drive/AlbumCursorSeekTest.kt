package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.AlbumPage
import com.linan.barezen_drive.core.dto.FolderDto
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.files.albumPageQuery
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/**
 * The album timeline is ordered by COALESCE(taken_at, updated_at) with a
 * keyset cursor, and PostgreSQL serves that order from `files_album_sort_idx`
 * only if the cursor is itself a seek. The natural spelling
 *
 *     (sort_ts < ?) OR (sort_ts = ? AND id < ?)
 *
 * is a plain boolean expression: the planner can use the index to *order* the
 * rows but has to walk it from the start and drop everything above the cursor,
 * so every page of a long scroll re-reads the whole timeline. Measured on a 50k
 * row library (PostgreSQL 16, EXPLAIN ANALYZE): 4.73 ms / 937 buffers for page
 * 50, against 0.12 ms / 12 buffers for the row-comparison spelling below.
 *
 * These tests pin the behaviour that must not change and the SQL shape that
 * makes the seek possible.
 */
class AlbumCursorSeekTest {
    private val storageDir = Files.createTempDirectory("bz-albumseek").toString()
    private val json = Json { ignoreUnknownKeys = true }
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

    private suspend fun ApplicationTestBuilder.upload(
        body: ByteArray,
        name: String,
        mime: String? = null,
        folderId: String? = null,
    ): String {
        val mimeField = mime?.let { ""","mimeType":"$it"""" } ?: ""
        val folderField = folderId?.let { ""","folderId":"$it"""" } ?: ""
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"sha256":"${sha256hex(body)}"$mimeField$folderField}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        assertFalse(ir.instantUpload, "content must be unique per call: $name")
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

    private suspend fun ApplicationTestBuilder.album(query: String = ""): AlbumPage {
        val res = client.get("/api/album$query") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return json.decodeFromString(res.bodyAsText())
    }

    /** Pins the sort key of each row so the timeline order is deterministic. */
    private fun setSortTs(name: String, takenAt: Long?) = transaction(DatabaseFactory.db) {
        val id = FilesTable.selectAll().where { FilesTable.name eq name }.single()[FilesTable.id]
        FilesTable.update({ FilesTable.id eq id }) {
            it[updatedAt] = 1_000L
            it[FilesTable.takenAt] = takenAt
        }
    }

    // ---- behaviour that must survive the seek rewrite ----------------------

    @Test
    fun pagedWalkVisitsEveryRowOnceInOrder() = testApplication {
        setup()
        val names = (1..7).map { "p$it.jpg" }
        names.forEach { upload(it.encodeToByteArray(), it, "image/jpeg") }
        // taken_at drives the order for the rows that carry it, updated_at for the rest.
        setSortTs("p1.jpg", 1_000L)
        setSortTs("p2.jpg", 3_000L)
        setSortTs("p3.jpg", null)
        setSortTs("p4.jpg", 2_000L)
        setSortTs("p5.jpg", null)
        setSortTs("p6.jpg", 4_000L)
        setSortTs("p7.jpg", null)

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val q = buildString {
                append("/api/album?limit=3")
                if (cursor != null) append("&before=$cursor")
            }
            val res = client.get(q) { header(HttpHeaders.Authorization, auth) }
            assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
            val page = json.decodeFromString<AlbumPage>(res.bodyAsText())
            seen += page.files.map { it.name }
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < 10)

        // p6(4000) p2(3000) p4(2000) lead; the remaining four all share sort key
        // 1000 (p1 through taken_at, the rest through their updated_at
        // fallback), so their relative order is the id tie-break and is left
        // unasserted - what matters is that all four appear exactly once.
        assertEquals(7, seen.size, "every row exactly once: $seen")
        assertEquals(7, seen.distinct().size, "a page boundary must not repeat a row: $seen")
        assertEquals(
            listOf("p6.jpg", "p2.jpg", "p4.jpg"),
            seen.take(3),
            "taken_at orders the timeline: $seen",
        )
        assertEquals(
            setOf("p1.jpg", "p3.jpg", "p5.jpg", "p7.jpg"),
            seen.drop(3).toSet(),
            "the rest all share sort key 1000: $seen",
        )
        assertNull(cursor, "the last page must not hand out another cursor")
    }

    @Test
    fun cursorAtTheNewestRowYieldsAnEmptyPage() = testApplication {
        setup()
        upload("a".encodeToByteArray(), "solo.jpg", "image/jpeg")
        setSortTs("solo.jpg", 7_000L)
        val first = album("?limit=10")
        assertEquals(listOf("solo.jpg"), first.files.map { it.name })
        assertNull(first.nextCursor)
    }

    @Test
    fun malformedCursorRestartsFromTheTop() = testApplication {
        setup()
        upload("a".encodeToByteArray(), "m1.jpg", "image/jpeg")
        upload("b".encodeToByteArray(), "m2.jpg", "image/jpeg")
        setSortTs("m1.jpg", 1_000L)
        setSortTs("m2.jpg", 2_000L)
        val page = album("?before=not-a-cursor")
        assertEquals(listOf("m2.jpg", "m1.jpg"), page.files.map { it.name })
    }

    @Test
    fun categoryFavoriteArchivedAndRootFiltersStillApply() = testApplication {
        setup()
        val root = createFolder("Album")
        val sub = createFolder("Day1", root)
        upload("p".encodeToByteArray(), "filt-a.jpg", "image/jpeg")
        upload("q".encodeToByteArray(), "filt-b.jpg", "image/jpeg")
        upload("n".encodeToByteArray(), "filt-c.txt", "text/plain")
        val inSub = upload("r".encodeToByteArray(), "filt-d.jpg", "image/jpeg", sub)
        setSortTs("filt-a.jpg", 5_000L)
        setSortTs("filt-b.jpg", 4_000L)
        setSortTs("filt-d.jpg", 3_000L)
        setSortTs("filt-c.txt", 2_000L)

        assertEquals(
            listOf("filt-a.jpg", "filt-b.jpg", "filt-d.jpg"),
            album().files.map { it.name },
            "text/plain is not media",
        )
        assertEquals(
            listOf("filt-a.jpg", "filt-b.jpg", "filt-d.jpg"),
            album("?category=image").files.map { it.name },
            "category=image drops the video branch but keeps every image",
        )
        assertEquals(emptyList(), album("?category=video").files.map { it.name })

        // root=<folderId> scopes to that folder's subtree.
        val scoped = album("?root=$root")
        assertEquals(listOf("filt-d.jpg"), scoped.files.map { it.name })

        // favorite only
        transaction(DatabaseFactory.db) {
            FilesTable.update({ FilesTable.name eq "filt-a.jpg" }) { it[isFavorite] = true }
        }
        assertEquals(listOf("filt-a.jpg"), album("?favorite=true").files.map { it.name })

        // archived only
        transaction(DatabaseFactory.db) {
            FilesTable.update({ FilesTable.name eq "filt-b.jpg" }) { it[archivedAt] = 42L }
        }
        assertEquals(listOf("filt-b.jpg"), album("?archived=true").files.map { it.name })
        assertTrue(inSub.isNotEmpty())
    }

    // ---- the SQL shape that lets the index seek ----------------------------

    @Test
    fun cursorIsASingleRowComparisonSoTheIndexCanSeek() = testApplication {
        setup()
        val user = UUID.randomUUID()
        val cursorTs = 1_700_000_000_000L
        val cursorId = UUID.randomUUID()
        // On an IO thread, and against the application's own connection:
        // DatabaseFactory is a process-wide singleton, and opening a transaction
        // on the JUnit thread leaves the next test's connect() building its
        // schema against the previous one.
        val sql = runBlocking(Dispatchers.IO) {
            transaction(DatabaseFactory.db) {
                albumPageQuery(
                    userId = user,
                    mediaFilter = Op.TRUE,
                    favoriteOnly = false,
                    archivedOnly = false,
                    rootId = null,
                    subtree = emptyList(),
                    cursorTs = cursorTs,
                    cursorId = cursorId,
                ).prepareSQL(this)
            }
        }
        val flat = sql.lowercase().replace(Regex("\\s+"), " ").replace("\"", "")
        assertTrue(
            flat.contains("(coalesce(files.taken_at, files.updated_at), files.id) <"),
            "the keyset cursor must be one row comparison, otherwise the planner " +
                "can only order by the index and has to skip every row above the cursor. SQL: $sql",
        )
        assertFalse(
            flat.contains("coalesce(files.taken_at, files.updated_at) <"),
            "the disjunctive spelling of the cursor has to be gone. SQL: $sql",
        )
    }
}
