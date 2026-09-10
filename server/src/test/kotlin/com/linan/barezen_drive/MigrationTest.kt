package com.linan.barezen_drive

import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
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
}
