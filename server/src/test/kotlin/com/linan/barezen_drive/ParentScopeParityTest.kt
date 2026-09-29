package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.files.FileService
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.get
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files as NioFiles
import java.util.UUID
import kotlin.test.*

/**
 * Every "which rows live under this parent" query was written twice - once
 * with `parent IS NULL` for the root level, once with `parent = ?` for a
 * folder - in `contents`, `nameConflict`, `findLiveFile` and
 * `hasFolderNamed`. Two copies of a filter is two places a fix has to land:
 * the NAME_CONFLICT carve-out for trashed rows, the exclude-self clause, the
 * live-only predicate, each written out by hand twice.
 *
 * These tests pin the *behaviour* of both sides rather than the shape of the
 * code: for every query that takes a nullable parent, the root answer and the
 * folder answer must come from the same rules. A parameterized branch that
 * forgot to carry one of the filters over shows up here as a mismatch between
 * the two levels, which is exactly how the duplicate would have drifted in
 * production.
 *
 * Because the two copies agreed before the merge as well, the parity tests
 * alone cannot fail on the old code - that is the point of a refactor. The
 * last test is the one that goes red on it: it counts the `IS NULL` /
 * `= ?` branch points in the source, which is the duplication itself.
 */
class ParentScopeParityTest {
    private val storageDir = NioFiles.createTempDirectory("bz-parity").toString()
    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
    )

    /** FileService.kt, located from the test's working directory either way. */
    private fun fileServiceSource(): String {
        val here = java.nio.file.Paths.get("").toAbsolutePath()
        val relative = java.nio.file.Paths.get("src/main/kotlin/com/linan/barezen_drive/files/FileService.kt")
        val found = listOf(here.resolve(relative), here.resolve("server").resolve(relative))
            .firstOrNull { java.nio.file.Files.exists(it) }
            ?: error("FileService.kt not found from $here")
        return String(java.nio.file.Files.readAllBytes(found))
    }

    @Test
    fun theRootLevelIsOneParameterizedBranchNotACopyPerQuery() {
        val source = fileServiceSource()
        // Everything outside the shared predicate: the one place that is
        // allowed to know a null parent means "the account root".
        val outsidePredicate = source
            .substringBefore("private fun scopedTo") + source.substringAfter("Op.build { if (parent == null)")
            .substringAfter("\n")
        assertEquals(
            0,
            Regex("if \\(parent == null\\)").findAll(outsidePredicate).count(),
            "no query may fork on a null parent by hand any more",
        )
        // The definition plus the six call sites that used to carry a copy:
        // contents (folders, files), nameConflict (folders, files),
        // findLiveFile, hasFolderNamed.
        assertEquals(
            7,
            Regex("scopedTo\\(").findAll(source).count(),
            "all six parent-scoped queries must go through the one predicate",
        )
    }

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

    private fun mkFile(user: UUID, folder: UUID?, name: String, deletedAt: Long = 0L): UUID {
        val id = UUID.randomUUID()
        transaction(DatabaseFactory.db) {
            FilesTable.insert {
                it[FilesTable.id] = id
                it[FilesTable.user] = user
                it[FilesTable.folder] = folder
                it[FilesTable.name] = name
                it[size] = 10L
                it[mimeType] = "text/plain"
                it[storageKey] = "blobs/$user/$name"
                // sha256 is varchar(64): the columns under test are the parent
                // filters, so the hash only has to be unique per row.
                it[sha256] = "sha-" + name.take(24) + "-" + deletedAt
                it[FilesTable.deletedAt] = deletedAt
                it[createdAt] = 1_700_000_000_000L + deletedAt
                it[updatedAt] = 1_700_000_000_000L + deletedAt
            }
        }
        return id
    }

    // The name/lookup helpers are internal and documented as running inside the
    // caller's transaction, the way the routes call them.
    private fun nameConflict(user: UUID, parent: UUID?, name: String, excludeFolder: UUID? = null, excludeFile: UUID? = null) =
        transaction(DatabaseFactory.db) { FileService.nameConflict(user, parent, name, excludeFolder, excludeFile) }

    private fun findLiveFile(user: UUID, parent: UUID?, name: String) =
        transaction(DatabaseFactory.db) { FileService.findLiveFile(user, parent, name) != null }

    private fun hasFolderNamed(user: UUID, parent: UUID?, name: String) =
        transaction(DatabaseFactory.db) { FileService.hasFolderNamed(user, parent, name) }

    /**
     * Two scopes holding the same shape of content: a root-level one and a
     * folder one, each with a file, a folder, a trashed file, a same-name
     * clash pair, and a name nobody uses.
     */
    private fun seedScope(user: UUID, parent: UUID?, tag: String) {
        mkFile(user, parent, "live-$tag")
        mkFile(user, parent, "trashed-$tag", deletedAt = 1_700_000_100_000L)
        mkFolder(user, parent, "dir-$tag")
        // A live file and a trashed file sharing a name is legal: only the live
        // one holds the name.
        mkFile(user, parent, "reused-$tag")
        mkFile(user, parent, "reused-$tag", deletedAt = 1_700_000_200_000L)
    }

    @Test
    fun nameConflictAppliesTheSameRulesAtTheRootAndInAFolder() = testApplication {
        setup()
        val user = newUser()
        val folder = mkFolder(user, null, "scope")
        seedScope(user, null, "root")
        seedScope(user, folder, "nested")

        // A live sibling file blocks the name at both levels...
        for (parent in listOf(null, folder)) {
            val tag = if (parent == null) "root" else "nested"
            assertTrue(
                nameConflict(user, parent, "live-$tag"),
                "a live sibling file holds the name at $tag",
            )
            assertTrue(
                nameConflict(user, parent, "dir-$tag"),
                "folders and files share one sibling namespace at $tag",
            )
            // ...but a trashed row does not: the same content can be uploaded
            // again while the old copy waits out the retention window.
            assertFalse(
                nameConflict(user, parent, "trashed-$tag"),
                "a trashed row must not hold the name at $tag",
            )
            assertFalse(nameConflict(user, parent, "free-$tag"), "an unused name is free at $tag")
        }
    }

    @Test
    fun nameConflictExcludesTheRowItselfAtBothLevels() = testApplication {
        setup()
        val user = newUser()
        val folder = mkFolder(user, null, "scope")
        seedScope(user, null, "root")
        seedScope(user, folder, "nested")

        for (parent in listOf(null, folder)) {
            val tag = if (parent == null) "root" else "nested"
            val live = transaction(DatabaseFactory.db) {
                FilesTable.selectAll()
                    .where { (FilesTable.user eq user) and (FilesTable.name eq "live-$tag") and (FilesTable.deletedAt eq 0L) }
                    .single()[FilesTable.id]
            }
            // Renaming a file to the name it already has must not 409.
            assertFalse(
                nameConflict(user, parent, "live-$tag", excludeFile = live),
                "the file does not clash with itself at $tag",
            )
            val dir = transaction(DatabaseFactory.db) {
                FoldersTable.selectAll().where { (FoldersTable.user eq user) and (FoldersTable.name eq "dir-$tag") }
                    .single()[FoldersTable.id]
            }
            assertFalse(
                nameConflict(user, parent, "dir-$tag", excludeFolder = dir),
                "the folder does not clash with itself at $tag",
            )
            // Excluding a different row must not hide the real clash.
            assertTrue(
                nameConflict(user, parent, "live-$tag", excludeFolder = dir),
                "excluding the folder must not mask the file clash at $tag",
            )
        }
    }

    @Test
    fun findLiveFileAndHasFolderNamedAgreeAcrossLevels() = testApplication {
        setup()
        val user = newUser()
        val folder = mkFolder(user, null, "scope")
        seedScope(user, null, "root")
        seedScope(user, folder, "nested")

        for (parent in listOf(null, folder)) {
            val tag = if (parent == null) "root" else "nested"
            assertTrue(findLiveFile(user, parent, "live-$tag"), "the live row is found at $tag")
            assertFalse(findLiveFile(user, parent, "trashed-$tag"), "a trashed row is not a replace target at $tag")
            assertTrue(hasFolderNamed(user, parent, "dir-$tag"), "the folder is seen at $tag")
            assertFalse(hasFolderNamed(user, parent, "nope-$tag"), "an unused name is absent at $tag")
        }
    }

    @Test
    fun aScopeOnlySeesItsOwnRows() = testApplication {
        setup()
        val user = newUser()
        val folder = mkFolder(user, null, "scope")
        val other = newUser()
        val otherFolder = mkFolder(other, null, "scope")
        seedScope(user, null, "root")
        seedScope(user, folder, "nested")
        seedScope(other, null, "otherroot")
        seedScope(other, otherFolder, "othernested")

        val rootPage = FileService.contents(user, "root")
        val nestedPage = FileService.contents(user, folder.toString())

        assertEquals(
            setOf("live-root", "reused-root"),
            rootPage.files.map { it.name }.toSet(),
            "the root listing is the root scope only",
        )
        assertEquals(
            setOf("live-nested", "reused-nested"),
            nestedPage.files.map { it.name }.toSet(),
            "the folder listing is that folder only",
        )
        assertEquals(setOf("dir-root", "scope"), rootPage.folders.map { it.name }.toSet(), "the root holds the nested scope's own folder")
        assertEquals(setOf("dir-nested"), nestedPage.folders.map { it.name }.toSet())
        assertTrue(rootPage.files.none { it.name.startsWith("other") }, "another account never leaks in")
        assertTrue(nestedPage.files.none { it.name.startsWith("other") })
        assertNull(rootPage.folder, "the root has no folder row")
        assertEquals(folder.toString(), nestedPage.folder?.id)
    }

    @Test
    fun creatingTheSameNameAtEitherLevelIsAConflict() = testApplication {
        setup()
        val user = newUser()
        val folder = mkFolder(user, null, "scope")
        seedScope(user, null, "root")
        seedScope(user, folder, "nested")

        assertFailsWith<com.linan.barezen_drive.api.ApiException>("root clash") {
            FileService.createFolder(user, null, "dir-root")
        }
        assertFailsWith<com.linan.barezen_drive.api.ApiException>("nested clash") {
            FileService.createFolder(user, folder.toString(), "dir-nested")
        }
        // The same name in a different scope is fine at either level.
        FileService.createFolder(user, folder.toString(), "dir-root")
        FileService.createFolder(user, null, "dir-nested")
    }

    @Test
    fun movingAndRenamingRespectTheDestinationScope() = testApplication {
        setup()
        val user = newUser()
        val folder = mkFolder(user, null, "scope")
        seedScope(user, null, "root")
        seedScope(user, folder, "nested")
        val movable = mkFile(user, null, "movable")

        val moved = FileService.updateFile(user, movable, null, folder.toString())
        assertEquals(folder.toString(), moved.folderId, "the DTO reports the destination it was moved to")
        assertTrue(
            FileService.contents(user, "root").files.none { it.id == movable.toString() },
            "the row is gone from the scope it was moved out of",
        )
        assertTrue(
            FileService.contents(user, folder.toString()).files.any { it.id == movable.toString() },
            "and present in the scope it was moved into",
        )

        // A rename that would clash inside the destination is refused; the same
        // name is free in the origin scope.
        val err = assertFailsWith<com.linan.barezen_drive.api.ApiException> {
            FileService.updateFile(user, movable, "live-nested", null)
        }
        assertEquals(com.linan.barezen_drive.core.dto.ErrorCodes.NAME_CONFLICT, err.code)
    }
}
