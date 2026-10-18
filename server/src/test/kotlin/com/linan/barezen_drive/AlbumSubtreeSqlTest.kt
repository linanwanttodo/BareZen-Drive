package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.files.albumPageQuery
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.get
import io.ktor.server.testing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Scoping the album timeline to a folder used to mean reading the account's whole
 * folder tree and pasting the descendants into one `IN (...)` - and doing it again
 * for every page, because a page cannot see the previous page's work. The read grew
 * with the number of folders and the parameter list grew with it, which is a cost of
 * *scrolling*, not of asking for something new.
 *
 * The expansion belongs in SQL. Like every other "same rows, different plan" change,
 * the rows are already covered by the behaviour tests, so what is pinned here is the
 * statement shape: the page carries the walk, and no `folders` row is read to build a
 * list first.
 */
class AlbumSubtreeSqlTest {
    private val storageDir = Files.createTempDirectory("bz-album-sql").toString()

    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
    )

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.get("/health")
    }

    private fun sqlOf(rootId: UUID?, user: UUID): String = runBlocking(Dispatchers.IO) {
        transaction(DatabaseFactory.db) {
            albumPageQuery(
                userId = user,
                mediaFilter = Op.TRUE,
                favoriteOnly = false,
                archivedOnly = false,
                rootId = rootId,
                cursorTs = null,
                cursorId = null,
            ).prepareSQL(this)
        }
    }.lowercase().replace(Regex("\\s+"), " ").replace("\"", "")

    @Test
    fun aRootedPageExpandsItsSubtreeInsideTheStatement() = testApplication {
        setup()
        val flat = sqlOf(UUID.randomUUID(), UUID.randomUUID())
        assertTrue(
            flat.contains("with recursive"),
            "the subtree must be resolved by the database, not by a list built in Kotlin. SQL: $flat",
        )
        assertFalse(
            Regex("folder_id in \\(\\?").containsMatchIn(flat),
            "an IN list of placeholder ids is the unbounded parameter cliff: $flat",
        )
    }

    @Test
    fun theAnchorIsScopedToTheOwningAccount() = testApplication {
        setup()
        // The root id comes from the query string. Without the owner in the anchor,
        // another account's folder id would expand *their* subtree and leak its file
        // names into this gallery - and the answer would look completely plausible.
        val flat = sqlOf(UUID.randomUUID(), UUID.randomUUID())
        val anchor = flat.substringAfter("subtree(id) as (select id from folders where")
        assertTrue(
            anchor.contains("user_id ="),
            "the walk has to start only at a folder this account owns. SQL: $flat",
        )
    }

    @Test
    fun anUnrootedPageCarriesNoSubtreeWalkAtAll() = testApplication {
        setup()
        val flat = sqlOf(null, UUID.randomUUID())
        assertFalse(
            flat.contains("with recursive"),
            "the whole-library timeline must not pay for a walk it never asked for: $flat",
        )
    }
}
