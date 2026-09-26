package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.get
import io.ktor.server.testing.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/**
 * The refcount sweep that decides which blobs may be unlinked asks "is this
 * storage_key still referenced?" - once per table. Both tables are the largest
 * in the schema, and neither had an index on `storage_key`, so every question
 * degenerated into a full scan: deleting a 1000-blob folder asked 2000 times,
 * and `orphanBlobKeys` runs twice per delete chain (once in hardDelete, once
 * after commit in the purge step). The cost is paid on the 5-connection pool
 * while holding it, which is exactly the shape of a self-inflicted outage.
 *
 * These tests pin that the indexes exist after a normal connect, so a future
 * migration cannot quietly drop them again.
 */
class StorageKeyIndexTest {

    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef",
        Files.createTempDirectory("bz-idx").toString(), 1L shl 30,
    )

    /** Index names covering `storage_key` on the two referencing tables. */
    private fun storageKeyIndexNames(jdbcUrl: String): Set<String> {
        java.sql.DriverManager.getConnection(jdbcUrl, "sa", "").use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES " +
                        "WHERE UPPER(TABLE_NAME) IN ('FILES','FILE_VERSIONS')",
                ).use { rs ->
                    val out = mutableSetOf<String>()
                    while (rs.next()) out += rs.getString(1).lowercase()
                    return out
                }
            }
        }
    }

    @Test
    fun filesAndVersionsBothIndexStorageKey() = testApplication {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.get("/health") // the module block is lazy; the DDL runs on start
        // Same in-memory URL as the app's pool, so this sees the schema the
        // migration actually built - no production code needs a test-only hook.
        val names = storageKeyIndexNames(c.jdbcUrl)
        val files = names.filter { it.startsWith("files") && "storage" in it }
        val versions = names.filter { it.startsWith("file_versions") && "storage" in it }
        assertTrue(files.isNotEmpty(), "files has no storage_key index (found: $names)")
        assertTrue(versions.isNotEmpty(), "file_versions has no storage_key index (found: $names)")
    }
}
