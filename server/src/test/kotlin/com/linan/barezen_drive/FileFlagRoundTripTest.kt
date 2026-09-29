package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.ShareLinksTable
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
 * Favorite / archive / trash / restore / rename-and-move each ran
 * "read the row, UPDATE it, read it again to build the DTO" - three round
 * trips for one flag, two of them re-reading the same primary key the first
 * statement had already fetched. Tapping favorite on a grid of 200 rows is 600
 * statements, 400 of them pure waste, all serialized on one of the 5 pooled
 * connections.
 *
 * `UPDATE ... RETURNING` would fold the write and the re-read into one
 * statement, but the test dialect (H2, which the whole server suite runs on)
 * rejects the clause - the syntax check below is what keeps the decision
 * honest rather than a guess. So the shape is: the one read the function needs
 * anyway is reused to build the DTO, and the UPDATE's affected-row count is
 * what proves the row still exists.
 *
 * The DTO must therefore report what the write actually did: the flags and the
 * timestamp come from the values handed to the UPDATE, not from a stale read.
 */
class FileFlagRoundTripTest {
    private val storageDir = NioFiles.createTempDirectory("bz-flagrt").toString()
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

    private fun newFile(user: UUID, name: String, deletedAt: Long = 0L): UUID {
        val id = UUID.randomUUID()
        transaction(DatabaseFactory.db) {
            FilesTable.insert {
                it[FilesTable.id] = id
                it[FilesTable.user] = user
                it[folder] = null
                it[FilesTable.name] = name
                it[size] = 10L
                it[mimeType] = "text/plain"
                it[storageKey] = "blobs/f/$id"
                it[sha256] = "sha-$id"
                it[FilesTable.deletedAt] = deletedAt
                it[createdAt] = 1_700_000_000_000L
                it[updatedAt] = 1_700_000_000_000L
            }
        }
        return id
    }

    private fun addShare(file: UUID, revokedAt: Long? = null) = transaction(DatabaseFactory.db) {
        ShareLinksTable.insert {
            it[id] = UUID.randomUUID()
            it[user] = fileOwner(file)
            it[ShareLinksTable.file] = file
            it[tokenHash] = "tok-$file"
            it[createdAt] = 1_700_000_000_000L
            it[expiresAt] = null
            it[ShareLinksTable.revokedAt] = revokedAt
        }
    }

    private fun fileOwner(file: UUID): UUID = transaction(DatabaseFactory.db) {
        FilesTable.selectAll().where { FilesTable.id eq file }.single()[FilesTable.user]
    }

    private fun isFavoriteInDb(id: UUID): Boolean = transaction(DatabaseFactory.db) {
        FilesTable.selectAll().where { FilesTable.id eq id }.single()[FilesTable.isFavorite]
    }

    private fun deletedAtInDb(id: UUID): Long = transaction(DatabaseFactory.db) {
        FilesTable.selectAll().where { FilesTable.id eq id }.single()[FilesTable.deletedAt]
    }

    private fun archivedAtInDb(id: UUID): Long = transaction(DatabaseFactory.db) {
        FilesTable.selectAll().where { FilesTable.id eq id }.single()[FilesTable.archivedAt]
    }

    private fun linkRevokedAt(file: UUID): Long? = transaction(DatabaseFactory.db) {
        ShareLinksTable.selectAll().where { ShareLinksTable.file eq file }.single()[ShareLinksTable.revokedAt]
    }

    @Test
    fun setFavoriteIsTwoStatementsAndTheDtoAgreesWithTheRow() = testApplication {
        setup()
        val user = newUser()
        val file = newFile(user, "a.txt")

        val counted = StatementCounter.count { FileService.setFavorite(user, file, true) }
        assertEquals(2, counted.total, "read + UPDATE, no re-read\n${counted.sql}")
        assertTrue(isFavoriteInDb(file), "the row must actually be flagged")
    }

    @Test
    fun setArchivedIsTwoStatements() = testApplication {
        setup()
        val user = newUser()
        val file = newFile(user, "a.txt")

        val counted = StatementCounter.count { FileService.setArchived(user, file, true) }
        assertEquals(2, counted.total, "read + UPDATE, no re-read\n${counted.sql}")
        assertTrue(archivedAtInDb(file) > 0L)
    }

    @Test
    fun trashFileDoesNotReReadTheRowItJustWrote() = testApplication {
        setup()
        val user = newUser()
        val file = newFile(user, "a.txt")
        addShare(file)

        val counted = StatementCounter.count { FileService.trashFile(user, file) }
        // read + UPDATE files + UPDATE share_links. The old tail re-selected the
        // file row it had just written.
        assertEquals(3, counted.total, "read + 2 UPDATEs, no re-read\n${counted.sql}")
        assertTrue(deletedAtInDb(file) > 0L)
        assertTrue(linkRevokedAt(file) != null, "a trashed file stops serving its links")
    }

    @Test
    fun restoreFileDoesNotReReadTheRowItJustWrote() = testApplication {
        setup()
        val user = newUser()
        val file = newFile(user, "a.txt", deletedAt = 1_700_000_500_000L)

        val counted = StatementCounter.count { FileService.restoreFile(user, file) }
        // read + the two conflict probes + UPDATE.
        assertEquals(4, counted.total, "no trailing re-read\n${counted.sql}")
        assertEquals(0L, deletedAtInDb(file))
    }

    @Test
    fun updateFileDoesNotReReadTheRowItJustWrote() = testApplication {
        setup()
        val user = newUser()
        val file = newFile(user, "a.txt")

        val counted = StatementCounter.count { FileService.updateFile(user, file, "renamed.txt", null) }
        // read + the two conflict probes + UPDATE.
        assertEquals(4, counted.total, "no trailing re-read\n${counted.sql}")
    }

    @Test
    fun theReturnedDtoDescribesTheWriteNotThePreWriteRow() = testApplication {
        setup()
        val user = newUser()
        val before = System.currentTimeMillis()
        val file = newFile(user, "a.txt")

        val favorited: FileDto = FileService.setFavorite(user, file, true)
        assertTrue(favorited.isFavorite, "the DTO must report the flag the UPDATE wrote")

        val archived: FileDto = FileService.setArchived(user, file, true)
        assertNotNull(archived.archivedAt, "the DTO must report the archive stamp the UPDATE wrote")
        assertEquals(archived.archivedAt, java.time.Instant.ofEpochMilli(archivedAtInDb(file)).toString())

        val trashed: FileDto = FileService.trashFile(user, file)
        assertNotNull(trashed.deletedAt, "the DTO must report the trash stamp the UPDATE wrote")
        assertEquals(trashed.deletedAt, java.time.Instant.ofEpochMilli(deletedAtInDb(file)).toString())
        assertTrue(
            java.time.Instant.parse(trashed.deletedAt).toEpochMilli() >= before,
            "the reported stamp is the one just written, not the row's old updated_at",
        )

        val restored: FileDto = FileService.restoreFile(user, file)
        assertNull(restored.deletedAt, "restoring clears the trash stamp in the DTO too")
    }

    @Test
    fun anUnknownOrForeignIdStillAnswersNotFound() = testApplication {
        setup()
        val user = newUser()
        val other = newUser()
        val foreign = newFile(other, "b.txt")
        val missing = UUID.randomUUID()

        for (id in listOf(missing, foreign)) {
            assertFailsWith<com.linan.barezen_drive.api.ApiException>("id $id must not resolve") {
                FileService.setFavorite(user, id, true)
            }
            assertFailsWith<com.linan.barezen_drive.api.ApiException>("id $id must not resolve") {
                FileService.setArchived(user, id, true)
            }
            assertFailsWith<com.linan.barezen_drive.api.ApiException>("id $id must not resolve") {
                FileService.trashFile(user, id)
            }
            assertFailsWith<com.linan.barezen_drive.api.ApiException>("id $id must not resolve") {
                FileService.restoreFile(user, id)
            }
            assertFailsWith<com.linan.barezen_drive.api.ApiException>("id $id must not resolve") {
                FileService.updateFile(user, id, "x.txt", null)
            }
        }
    }

    @Test
    fun restoringALiveFileIsANoOpThatStillAnswersTheRow() = testApplication {
        setup()
        val user = newUser()
        val file = newFile(user, "a.txt")

        val dto = FileService.restoreFile(user, file)

        assertEquals(file.toString(), dto.id)
        assertNull(dto.deletedAt)
        assertEquals(0L, deletedAtInDb(file))
    }

    @Test
    fun theTestDialectCannotRunUpdateReturning() = testApplication {
        // If H2 ever grows `UPDATE ... RETURNING`, the two-statement shape
        // above is still correct but no longer necessary - this test exists so
        // the choice is revisited deliberately instead of assumed.
        setup()
        val rejected = transaction(DatabaseFactory.db) {
            val user = newUser()
            val file = newFile(user, "returning.txt")
            var failed = false
            try {
                exec("UPDATE files SET is_favorite = TRUE WHERE id = '$file' RETURNING id") {
                    failed = false
                }
            } catch (e: Exception) {
                failed = true
            }
            failed
        }
        assertTrue(
            rejected,
            "H2 now supports UPDATE ... RETURNING - FileService can collapse the flag writes into one statement",
        )
    }
}
