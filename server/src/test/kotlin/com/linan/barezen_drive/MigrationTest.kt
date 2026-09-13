package com.linan.barezen_drive

import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for the production migration failure seen when upgrading a
 * v0.0.1 database: ALTER TABLE files ADD has_thumbnail BOOLEAN NOT NULL
 * (no DEFAULT) fails on any table with existing rows. The column must carry a
 * SQL-level DEFAULT so the ALTER backfills existing rows instead of aborting.
 *
 * H2 in PostgreSQL mode is close enough to production DDL semantics for this
 * check; the same statement shape was rejected by real PostgreSQL 16.
 */
class MigrationTest {
    @Test
    fun addNotNullThumbnailColumnBackfillsExistingRows() {
        val jdbcUrl = "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn: Connection ->
            // Recreate the v0.0.1 files table shape: no has_thumbnail column.
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE users (
                        id UUID PRIMARY KEY,
                        username VARCHAR(32) NOT NULL,
                        password_hash VARCHAR(255) NOT NULL,
                        created_at BIGINT NOT NULL,
                        CONSTRAINT users_username_unique UNIQUE (username)
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE folders (
                        id UUID PRIMARY KEY,
                        user_id UUID NOT NULL,
                        parent_id UUID NULL,
                        name VARCHAR(255) NOT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE files (
                        id UUID PRIMARY KEY,
                        user_id UUID NOT NULL,
                        folder_id UUID NULL,
                        name VARCHAR(255) NOT NULL,
                        size BIGINT NOT NULL,
                        mime_type VARCHAR(255) NULL,
                        sha256 VARCHAR(64) NOT NULL,
                        storage_key VARCHAR(512) NOT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """INSERT INTO users VALUES ('11111111-1111-1111-1111-111111111111', 'legacy', 'x', 1)""",
                )
                // A pre-existing row: the NOT NULL add must backfill it, not fail.
                st.execute(
                    """
                    INSERT INTO files VALUES (
                        '22222222-2222-2222-2222-222222222222',
                        '11111111-1111-1111-1111-111111111111',
                        NULL, 'old.txt', 5, 'text/plain',
                        '${"a".repeat(64)}', 'blobs/aa/${"a".repeat(64)}', 1, 1
                    )
                    """.trimIndent(),
                )
            }

            val db = DatabaseFactory.connect(jdbcUrl, "sa", "")
            // Must not throw: the has_thumbnail ADD now carries DEFAULT FALSE.
            transaction(db) { SchemaUtils.createMissingTablesAndColumns(*DatabaseFactory.ALL_TABLES) }

            transaction(db) {
                val row = FilesTable.selectAll().where { FilesTable.id eq java.util.UUID.fromString("22222222-2222-2222-2222-222222222222") }.single()
                assertFalse(row[FilesTable.hasThumbnail], "backfilled row must default to false")
            }

            // Share links table arrives on old databases as a plain CREATE.
            val shares = conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM share_links").use { rs -> rs.next(); rs.getInt(1) }
            }
            assertEquals(0, shares)
            assertTrue(conn.createStatement().use { st ->
                st.execute("SELECT 1 FROM users WHERE username = 'legacy'")
            }, "legacy data must survive the migration")
        }
    }

    /**
     * The gallery-flag upgrade: files gains is_favorite / archived_at /
     * deleted_at, and the sibling-name uniqueness index moves from three
     * columns to four (live rows share deleted_at = 0 while trashed ones carry
     * their own timestamp). The stale three-column index must be dropped during
     * startup, or a file sitting in the trash would keep blocking a re-upload
     * of the same name.
     */
    @Test
    fun trashColumnsAndNameIndexMigration() {
        val jdbcUrl = "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
        val userId = java.util.UUID.fromString("44444444-4444-4444-4444-444444444444")
        val legacyId = java.util.UUID.fromString("33333333-3333-3333-3333-333333333333")
        DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE users (
                        id UUID PRIMARY KEY,
                        username VARCHAR(32) NOT NULL,
                        password_hash VARCHAR(255) NOT NULL,
                        created_at BIGINT NOT NULL,
                        CONSTRAINT users_username_unique UNIQUE (username)
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE folders (
                        id UUID PRIMARY KEY,
                        user_id UUID NOT NULL,
                        parent_id UUID NULL,
                        name VARCHAR(255) NOT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE files (
                        id UUID PRIMARY KEY,
                        user_id UUID NOT NULL,
                        folder_id UUID NULL,
                        name VARCHAR(255) NOT NULL,
                        size BIGINT NOT NULL,
                        mime_type VARCHAR(255) NULL,
                        sha256 VARCHAR(64) NOT NULL,
                        storage_key VARCHAR(512) NOT NULL,
                        has_thumbnail BOOLEAN NOT NULL DEFAULT FALSE,
                        taken_at BIGINT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """.trimIndent(),
                )
                // The pre-gallery schema: uniqueness over (user, folder, name) only.
                st.execute("CREATE UNIQUE INDEX files_legacy_name_uniq ON files (user_id, folder_id, name)")
                st.execute("""INSERT INTO users VALUES ('$userId', 'old', 'x', 1)""")
                st.execute(
                    """
                    INSERT INTO files VALUES (
                        '$legacyId', '$userId', NULL, 'a.txt', 1, 'text/plain',
                        '${"b".repeat(64)}', 'blobs/bb/${"b".repeat(64)}', FALSE, NULL, 1, 1
                    )
                    """.trimIndent(),
                )
            }

            val db = DatabaseFactory.connect(jdbcUrl, "sa", "")

            transaction(db) {
                val row = FilesTable.selectAll().where { FilesTable.id eq legacyId }.single()
                assertFalse(row[FilesTable.isFavorite], "backfilled row must not be favorited")
                assertEquals(0L, row[FilesTable.archivedAt], "backfilled row must be live, not archived")
                assertEquals(0L, row[FilesTable.deletedAt], "backfilled row must be live, not trashed")
            }

            val uniqueIndexes = mutableMapOf<String, MutableSet<String>>()
            conn.metaData.getIndexInfo(null, null, "files", false, false).use { rs ->
                while (rs.next()) {
                    val index = rs.getString("INDEX_NAME") ?: continue
                    val column = rs.getString("COLUMN_NAME") ?: continue
                    if (rs.getBoolean("NON_UNIQUE")) continue
                    uniqueIndexes.getOrPut(index) { mutableSetOf() }.add(column.lowercase())
                }
            }
            val columnSets = uniqueIndexes.values.map { it }.toSet()
            assertFalse(
                columnSets.any { it == setOf("user_id", "folder_id", "name") },
                "the three-column name index must be dropped, found: $uniqueIndexes",
            )
            assertTrue(
                columnSets.any { it == setOf("user_id", "folder_id", "name", "deleted_at") },
                "the four-column name index must exist, found: $uniqueIndexes",
            )

            // The point of the whole exercise: a trashed row does not reserve the name.
            transaction(db) {
                FilesTable.update { it[deletedAt] = 5L }
                FilesTable.insert {
                    it[FilesTable.id] = java.util.UUID.randomUUID()
                    it[user] = userId
                    it[folder] = null
                    it[name] = "a.txt"
                    it[size] = 2L
                    it[sha256] = "c".repeat(64)
                    it[storageKey] = "blobs/cc/${"c".repeat(64)}"
                }
                assertEquals(2L, FilesTable.selectAll().where { FilesTable.name eq "a.txt" }.count())
            }
        }
    }
}
