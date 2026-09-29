package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.UploadChunksTable
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.files.FileService
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.get
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files as NioFiles
import java.util.UUID
import kotlin.test.*

/**
 * Deleting a folder subtree issued one `DELETE FROM folders WHERE id = ?` per
 * folder. A deep or wide tree therefore cost one round trip per folder - all of
 * them on the same pooled connection (5 for the whole process), for a delete the
 * user perceives as one click.
 *
 * The subtree is walked breadth-first anyway, so the ids are already grouped by
 * depth. Deleting one depth at a time with `WHERE id IN (...)` keeps the
 * self-referencing parent FK satisfiable (children first) while the statement
 * count tracks the tree's *depth* instead of its size.
 */
class FolderSubtreeBatchDeleteTest {
    private val storageDir = NioFiles.createTempDirectory("bz-subtree").toString()
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

    private fun mkFolder(user: UUID, parent: UUID?): UUID {
        val id = UUID.randomUUID()
        transaction(DatabaseFactory.db) {
            FoldersTable.insert {
                it[FoldersTable.id] = id
                it[FoldersTable.user] = user
                it[FoldersTable.parent] = parent
                it[FoldersTable.name] = "d-${id.toString().take(8)}"
            }
        }
        return id
    }

    /**
     * A tree with the given per-level width: level 0 is the root alone, level 1
     * holds [width] folders under it, and so on. Returns the root id.
     */
    private fun buildTree(user: UUID, width: Int, depth: Int): UUID {
        val root = mkFolder(user, null)
        var level = listOf(root)
        repeat(depth) {
            level = level.flatMap { parent -> (1..width).map { mkFolder(user, parent) } }
        }
        return root
    }

    private fun folderIds(user: UUID): List<UUID> = transaction(DatabaseFactory.db) {
        FoldersTable.selectAll().where { FoldersTable.user eq user }.map { it[FoldersTable.id] }
    }

    /** An upload session (plus a chunk) parked on [folder]; the delete must take it. */
    private fun parkSession(user: UUID, folder: UUID): UUID {
        val sid = UUID.randomUUID()
        transaction(DatabaseFactory.db) {
            UploadSessionsTable.insert {
                it[id] = sid
                it[UploadSessionsTable.user] = user
                it[UploadSessionsTable.folder] = folder
                it[name] = "pending.bin"
                it[size] = 1L
                it[chunkSize] = 1024L
                it[clientSha256] = null
                it[takenAt] = null
                it[expiresAt] = System.currentTimeMillis() + 100_000
            }
            UploadChunksTable.insert {
                it[UploadChunksTable.session] = sid
                it[UploadChunksTable.chunkIndex] = 0
                it[UploadChunksTable.size] = 1
            }
        }
        return sid
    }

    private fun countSessions(): Int = transaction(DatabaseFactory.db) { UploadSessionsTable.selectAll().count().toInt() }
    private fun countChunks(): Int = transaction(DatabaseFactory.db) { UploadChunksTable.selectAll().count().toInt() }

    @Test
    fun aWideSubtreeCostsNoMoreStatementsThanANarrowOne() = testApplication {
        setup()
        val narrowUser = newUser()
        val narrowRoot = buildTree(narrowUser, width = 2, depth = 2) // 1 + 2 + 4 = 7 folders
        val wideUser = newUser()
        val wideRoot = buildTree(wideUser, width = 40, depth = 2) // 1 + 40 + 1600 = 1641 folders

        val narrow = StatementCounter.count { FileService.deleteFolder(narrowUser, narrowRoot) }
        val wide = StatementCounter.count { FileService.deleteFolder(wideUser, wideRoot) }

        // Before: one DELETE per folder, so the wide tree cost 1641 statements
        // against 7 for the narrow one. Now the count follows the depth (3
        // levels) plus the handful of non-folder statements both trees share.
        val folderDeletes = wide.of("DELETE", "from folders")
        assertTrue(
            folderDeletes.size <= 3,
            "1641 folders must not cost 1641 folder deletes, took ${folderDeletes.size}\n${wide.sql}",
        )
        assertTrue(
            wide.total <= narrow.total + 6,
            "cost grew with the tree size: 7 folders = ${narrow.total}, 1641 folders = ${wide.total}\n${wide.sql}",
        )
    }

    @Test
    fun aDeepSubtreeCostsOneDeletePerLevel() = testApplication {
        setup()
        val user = newUser()
        // A chain: every folder is its own level, so a per-level delete is the
        // worst case - and the parent FK means it cannot be collapsed further.
        var deepest = mkFolder(user, null)
        val top = deepest
        repeat(30) { deepest = mkFolder(user, deepest) }
        val counted = StatementCounter.count { FileService.deleteFolder(user, top) }
        val folderDeletes = counted.of("DELETE", "from folders")
        assertEquals(31, folderDeletes.size, "one DELETE per depth level\n${folderDeletes}")
        assertEquals(0, folderIds(user).size, "every folder in the chain must be gone")
    }

    @Test
    fun theWholeSubtreeDisappearsWhileSiblingsAndOtherAccountsStay() = testApplication {
        setup()
        val user = newUser()
        val root = buildTree(user, width = 3, depth = 3) // 1 + 3 + 9 + 27 = 40
        val siblingOfRoot = mkFolder(user, null)
        val otherUser = newUser()
        val otherRoot = buildTree(otherUser, width = 2, depth = 1) // 3 folders
        val otherSurvivor = mkFolder(otherUser, null)
        val otherIds = folderIds(otherUser)

        FileService.deleteFolder(user, root)

        assertEquals(
            listOf(siblingOfRoot).sorted(),
            folderIds(user).sorted(),
            "only the sibling of the deleted root survives",
        )
        assertEquals(
            4,
            otherIds.size,
            "sanity: the other account's tree is 3 folders plus its own root sibling",
        )
        assertEquals(
            otherIds.sorted(),
            folderIds(otherUser).sorted(),
            "another account's folders are never in the subtree",
        )
    }

    @Test
    fun sessionsAndChunksTargetingTheSubtreeGoWithIt() = testApplication {
        setup()
        val user = newUser()
        val root = buildTree(user, width = 2, depth = 2) // 7 folders
        val parked = folderIds(user).map { parkSession(user, it) }
        assertEquals(7, countSessions())
        assertEquals(7, countChunks())

        val deletion = FileService.deleteFolder(user, root)

        assertEquals(0, countSessions(), "no session may survive the subtree delete")
        assertEquals(0, countChunks())
        assertEquals(7, deletion.removedSessionIds.size, "the caller is told which sessions went away")
        assertTrue(deletion.removedSessionIds.containsAll(parked))
    }
}
