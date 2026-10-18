package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.BlobDeleteQueueTable
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FileVersionsTable
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.files.FileService
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.get
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two shapes of "the statement is only as big as the data lets it be", both of which
 * looked fine while the test data was small:
 *
 * - the trash purge loaded *every* row it was about to delete into one list and put
 *   that list into a single `IN (...)`. PostgreSQL refuses a statement past 65 535
 *   bind parameters, so a long-lived account's nightly cleanup starts failing outright
 *   - and the memory needed to build the list was the whole trash.
 * - copying a folder issued one INSERT per folder row and one per file row, all inside
 *   one transaction: a 3 000-row tree was 3 000 sequential round trips on one of five
 *   pooled connections, and the copy failed as a whole if any single one of them broke.
 *
 * The batch size is a parameter of these paths, so a test can watch the batching happen
 * with a handful of rows instead of sixty-five thousand.
 */
class DbBatchedWriteTest {
    private val storageDir = Files.createTempDirectory("bz-batch").toString()
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

    private fun newUser(): UUID = transaction(DatabaseFactory.db) {
        val uid = UUID.randomUUID()
        UsersTable.insert {
            it[id] = uid
            it[username] = "u-${uid.toString().take(8)}"
            it[passwordHash] = "x"
            it[createdAt] = System.currentTimeMillis()
        }
        uid
    }

    private fun mkFolder(user: UUID, parent: UUID?, name: String): UUID {
        val id = UUID.randomUUID()
        transaction(DatabaseFactory.db) {
            FoldersTable.insert {
                it[FoldersTable.id] = id
                it[FoldersTable.user] = user
                it[FoldersTable.parent] = parent
                it[FoldersTable.name] = name
            }
        }
        return id
    }

    /** A row with its own blob; trashed rows also own a version snapshot, which the
     *  purge has to take with them. */
    private fun mkFile(user: UUID, folder: UUID?, name: String, deletedAt: Long = 0L): String {
        val key = "blobs/$name-${UUID.randomUUID()}"
        transaction(DatabaseFactory.db) {
            val id = FilesTable.insert {
                it[FilesTable.id] = UUID.randomUUID()
                it[FilesTable.user] = user
                it[FilesTable.folder] = folder
                it[FilesTable.name] = name
                it[FilesTable.size] = 3L
                it[FilesTable.sha256] = name.hashCode().toString()
                it[FilesTable.storageKey] = key
                it[FilesTable.deletedAt] = deletedAt
            }.get(FilesTable.id)
            if (deletedAt > 0L) {
                FileVersionsTable.insert {
                    it[FileVersionsTable.id] = UUID.randomUUID()
                    it[FileVersionsTable.file] = id
                    it[FileVersionsTable.revision] = 1
                    it[FileVersionsTable.storageKey] = "$key-v1"
                    it[FileVersionsTable.size] = 3L
                    it[FileVersionsTable.sha256] = "v1"
                }
            }
        }
        return key
    }

    private fun fileCount(user: UUID): Int = transaction(DatabaseFactory.db) {
        FilesTable.selectAll().where { FilesTable.user eq user }.count().toInt()
    }

    private fun versionCount(): Int = transaction(DatabaseFactory.db) { FileVersionsTable.selectAll().count().toInt() }

    /** Bind parameters in one statement: a `?` per value the server has to be told. */
    private fun placeholders(sql: String): Int = sql.count { it == '?' }

    @Test
    fun theTrashPurgeWalksItsBatchInsteadOfLoadingTheWholeTrash() = testApplication {
        setup()
        val user = newUser()
        val folder = mkFolder(user, null, "docs")
        val keys = (1..7).map { mkFile(user, folder, "t$it.txt", deletedAt = 1L) }
        assertEquals(7, versionCount(), "each trashed row also owns a version snapshot")

        val purged = ArrayList<String>()
        val counted = StatementCounter.count {
            // retentionDays 0 makes every trashed row expired; batch 2 forces the loop.
            purged += FileService.purgeExpiredTrash(retentionDays = 0, batch = 2)
        }

        assertEquals(0, fileCount(user), "every expired row must be gone, not just the first batch")
        assertEquals(0, versionCount(), "and its version snapshots with it")
        // A purge reports the file's own blob *and* the version snapshot's, because
        // both lost their last reference - that is not new, and it is why the count
        // is twice the rows.
        assertEquals(
            keys.flatMap { listOf(it, "$it-v1") }.toSet(),
            purged.toSet(),
            "each blob that lost its last reference is reported once",
        )

        val deletes = counted.of("DELETE", "from files")
        assertTrue(deletes.size >= 4, "7 rows at batch 2 cannot be one statement: ${counted.sql}")
        deletes.forEach {
            assertTrue(
                placeholders(it) <= 2,
                "an IN list wider than the batch is the parameter cliff all over again: $it",
            )
        }
        // The page read is the unbounded one; the refcount lookup beside it is
        // already bounded by the batch it belongs to.
        val pages = counted.of("SELECT", "files.id, files.storage_key")
        assertTrue(pages.size >= 4, "7 rows at batch 2 is several pages: ${counted.sql}")
        pages.forEach {
            assertTrue(it.contains("LIMIT", ignoreCase = true), "every page of the purge is bounded: $it")
        }
    }

    @Test
    fun emptyingTheTrashIsBatchedToo() = testApplication {
        setup()
        val user = newUser()
        val other = newUser()
        val folder = mkFolder(user, null, "docs")
        val mine = (1..5).map { mkFile(user, folder, "e$it.txt", deletedAt = 1L) }
        mkFile(other, null, "keep.txt", deletedAt = 1L)

        val keys = ArrayList<String>()
        StatementCounter.count { keys += FileService.emptyTrash(user, batch = 2) }

        assertEquals(
            mine.flatMap { listOf(it, "$it-v1") }.toSet(),
            keys.toSet(),
            "only this account's trash is emptied",
        )
        assertEquals(0, fileCount(user))
        assertEquals(1, fileCount(other), "another account's trash is not this call's business")
    }

    @Test
    fun copyingATreeWritesEachTableInBatchedStatements() = testApplication {
        setup()
        val user = newUser()
        val root = mkFolder(user, null, "src")
        val children = (1..4).map { mkFolder(user, root, "c$it") }
        val grandchildren = children.flatMap { c -> (1..4).map { mkFolder(user, c, "g$it") } }
        val allFolders = listOf(root) + children + grandchildren // 21 rows, three levels
        allFolders.forEachIndexed { i, f -> mkFile(user, f, "f$i.txt") }

        var copy: UUID? = null
        val counted = StatementCounter.count {
            copy = FileService.copySubtree(user, root, null, "copy")
        }
        val newRoot = copy ?: error("the copy returned no id")

        // What a test can see here is the *statements*, not the round trips: on H2
        // the batched insert is reported once per row even though it goes out as one
        // JDBC batch, so the count says nothing about the batching. The copied shape
        // is what the rewrite can break, and that is pinned below; the round-trip
        // saving was measured on PostgreSQL 16 instead, where a 336-row copy of this
        // same shape reaches the server as three multi-row INSERTs.
        assertTrue(
            counted.of("SELECT", "from files").size <= 2,
            "the subtree's files are read by batch, not by row: ${counted.of("SELECT", "from files")}",
        )

        transaction(DatabaseFactory.db) {
            assertEquals(
                allFolders.size.toLong() * 2,
                FoldersTable.selectAll().where { FoldersTable.user eq user }.count(),
                "the tree was copied, not moved",
            )
            assertEquals(
                allFolders.size.toLong() * 2,
                FilesTable.selectAll().where { FilesTable.user eq user }.count(),
                "every file row was duplicated, and only those",
            )
            // The shape, not just the count: a child written with a parent that is
            // still the *old* id would land in the source tree instead of the copy.
            val copiedNames = FoldersTable.select(FoldersTable.name).where {
                FoldersTable.user eq user and (FoldersTable.parent eq newRoot)
            }.map { it[FoldersTable.name] }.sorted()
            assertEquals((1..4).map { "c$it" }.sorted(), copiedNames)
            assertEquals(
                emptyList(),
                BlobDeleteQueueTable.selectAll().map { it[BlobDeleteQueueTable.storageKey] },
                "a copy shares its blobs: nothing here may be queued for deletion",
            )
        }
    }
}
