package com.linan.barezen_drive

import com.linan.barezen_drive.auth.AuthService
import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.TrashResponse
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files as NioFiles
import java.util.UUID
import kotlin.test.*

/**
 * `GET /api/trash` used to answer with every trashed row the account owned.
 * Each row is a 12-field DTO with three timestamps stringified, so deleting ten
 * thousand photos and then opening the trash screen meant one response, one
 * deserialization and one unbounded list build on both sides - for a screen
 * that shows a grid of cards.
 *
 * Paging is a keyset walk on (deleted_at DESC, id DESC): the same shape the
 * album endpoint uses, so a row trashed or restored between two pages shifts
 * the cursor by at most one row instead of silently dropping or repeating
 * pages the way an OFFSET would.
 *
 * The default page is a hard bound for callers that pass nothing - the old
 * client shape (`{files:[...]}` with no cursor) still decodes, it just gets the
 * newest 100 rows and a cursor it can follow.
 */
class TrashPagingTest {
    private val storageDir = NioFiles.createTempDirectory("bz-trashpage").toString()
    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
    )
    private var auth = ""
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.get("/health") // the module block is lazy; the schema comes from it
        // Registered through the service, not the route: the public sign-up
        // endpoint is a product decision this test has no stake in.
        val user = AuthService.register("trasher", "password123")
        auth = "Bearer " + AuthService.issueTokens(UUID.fromString(user.id)).accessToken
    }

    private fun trashedIdsOf(): List<UUID> {
        val uid = currentUserId()
        return transaction(DatabaseFactory.db) {
            FilesTable.selectAll().where { FilesTable.user eq uid }.map { it[FilesTable.id] }
        }
    }

    /**
     * [count] trashed rows for the signed-in account. [baseMs] pins the
     * deleted_at stamp so a test can reason about the order without sleeping;
     * rows created in the same millisecond are the case the id tiebreaker in
     * the cursor exists for.
     */
    private fun seedTrashed(count: Int, baseMs: Long = 1_700_000_000_000L, sameMillisecond: Boolean = false) {
        val uid = currentUserId()
        transaction(DatabaseFactory.db) {
            repeat(count) { i ->
                FilesTable.insert {
                    it[id] = UUID.randomUUID()
                    it[user] = uid
                    it[folder] = null
                    it[name] = "t-$i.txt"
                    it[size] = 10L
                    it[mimeType] = "text/plain"
                    it[storageKey] = "blobs/t/$i"
                    it[sha256] = "sha-t-$i"
                    it[deletedAt] = if (sameMillisecond) baseMs else baseMs + i
                    it[createdAt] = baseMs
                    it[updatedAt] = baseMs
                }
            }
        }
    }

    private fun currentUserId(): UUID = transaction(DatabaseFactory.db) {
        UsersTable.selectAll().where { UsersTable.username eq "trasher" }.single()[UsersTable.id]
    }

    private suspend fun ApplicationTestBuilder.trash(limit: Int? = null, cursor: String? = null): TrashResponse {
        val query = buildString {
            if (limit != null) append("?limit=$limit")
            if (cursor != null) append(if (isEmpty()) "?" else "&").append("cursor=").append(cursor)
        }
        val res = client.get("/api/trash$query") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return json.decodeFromString(res.bodyAsText())
    }

    @Test
    fun theDefaultResponseIsBoundedToOnePage() = testApplication {
        setup()
        seedTrashed(250)

        val page = trash()

        assertTrue(
            page.files.size in 1..100,
            "an unpaged request must not return the whole trash, got ${page.files.size} rows",
        )
        assertNotNull(page.nextCursor, "a truncated page must hand back a cursor to continue with")
    }

    @Test
    fun theCursorWalksEveryTrashedRowExactlyOnce() = testApplication {
        setup()
        seedTrashed(250)
        val expected = trashedIdsOf().toSet()

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = trash(limit = 100, cursor = cursor)
            assertTrue(page.files.size <= 100, "a page may not exceed the limit")
            seen += page.files.map { it.id }
            cursor = page.nextCursor
            pages++
            assertTrue(pages < 10, "the cursor must terminate")
        } while (cursor != null)

        assertEquals(3, pages, "250 rows at 100 per page is three requests")
        assertEquals(250, seen.size)
        assertEquals(250, seen.distinct().size, "a page boundary must not repeat a row")
        assertEquals(expected, seen.toSet().map { UUID.fromString(it) }.toSet(), "paging must cover exactly the same set")
    }

    @Test
    fun rowsSharingADeletedAtTimestampAreAllReachable() = testApplication {
        setup()
        // 250 rows with an identical deleted_at: without the id tiebreaker in
        // the cursor the walk would loop on the first page forever.
        seedTrashed(250, sameMillisecond = true)
        val expected = trashedIdsOf().toSet()

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = trash(limit = 60, cursor = cursor)
            seen += page.files.map { it.id }
            cursor = page.nextCursor
            pages++
            assertTrue(pages < 20, "the cursor must terminate")
        } while (cursor != null)

        assertEquals(250, seen.distinct().size, "no row may be skipped or repeated")
        assertEquals(expected, seen.toSet().map { UUID.fromString(it) }.toSet())
    }

    @Test
    fun pagesAreOrderedNewestTrashedFirst() = testApplication {
        setup()
        seedTrashed(30)

        val all = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = trash(limit = 7, cursor = cursor)
            all += page.files.map { it.id }
            cursor = page.nextCursor
        } while (cursor != null)

        val first = trash(limit = 30)
        assertEquals(first.files.map { it.id }, all, "paging must reproduce the unpaged order")
        // Compared as instants, not strings: Instant.toString() drops trailing
        // zeros, so "…:20Z" and "…:20.029Z" do not sort lexicographically.
        val stamps = first.files.map { java.time.Instant.parse(it.deletedAt!!).toEpochMilli() }
        assertEquals(stamps.sortedDescending(), stamps, "the head of the list is the newest trash entry")
    }

    @Test
    fun aMalformedCursorRestartsFromTheTop() = testApplication {
        setup()
        seedTrashed(20)

        val page = trash(limit = 5, cursor = "not-a-cursor")

        assertEquals(5, page.files.size)
        assertEquals(
            trash(limit = 5).files.map { it.id },
            page.files.map { it.id },
            "garbage in, a full first page out - same contract as the album page",
        )
    }

    @Test
    fun anotherAccountsTrashIsNeverOnThePage() = testApplication {
        setup()
        seedTrashed(3)
        // A second account with trashed rows of its own.
        val other = transaction(DatabaseFactory.db) {
            val uid = UUID.randomUUID()
            UsersTable.insert {
                it[id] = uid
                it[username] = "intruder"
                it[passwordHash] = "x"
                it[createdAt] = System.currentTimeMillis()
            }
            repeat(4) { i ->
                FilesTable.insert {
                    it[id] = UUID.randomUUID()
                    it[user] = uid
                    it[folder] = null
                    it[name] = "other-$i.txt"
                    it[size] = 10L
                    it[mimeType] = "text/plain"
                    it[storageKey] = "blobs/o/$i"
                    it[sha256] = "sha-o-$i"
                    it[deletedAt] = 1_700_000_000_000L + i
                    it[createdAt] = 1_700_000_000_000L
                    it[updatedAt] = 1_700_000_000_000L
                }
            }
            uid
        }

        val page = trash(limit = 100)

        assertEquals(3, page.files.size, "the second account's four rows are not in this page")
        assertTrue(page.files.none { it.name.startsWith("other-") })
        assertTrue(other != currentUserId())
    }

    @Test
    fun anEmptyTrashHasNoCursor() = testApplication {
        setup()
        val page = trash()
        assertEquals(0, page.files.size)
        assertNull(page.nextCursor, "nothing to continue from")
    }
}
