package com.linan.barezen_drive.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object DatabaseFactory {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    val ALL_TABLES = arrayOf(UsersTable, RefreshTokensTable, FoldersTable, FilesTable, UploadSessionsTable, UploadChunksTable, FileVersionsTable, ShareLinksTable, SettingsTable)

    // Held so connect() can shut the previous pool down: every testApplication
    // entry calls connect() again, and swapping the global db reference without
    // closing leaked a live Hikari pool per application instance.
    private var pool: HikariDataSource? = null

    // Exposed 0.61 registers the manager into the CALLING thread's ThreadLocal, so the
    // no-arg transaction {} form fails on other threads (Netty event loops). Every call
    // site must therefore pass this instance explicitly: transaction(DatabaseFactory.db).
    lateinit var db: Database
        private set

    /** True once connect() has run on this process. */
    val connected: Boolean get() = ::db.isInitialized

    fun connect(jdbcUrl: String, user: String, password: String): Database {
        runCatching { pool?.close() }
        val ds = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password
            maximumPoolSize = 5
            isAutoCommit = false
        })
        pool = ds
        db = Database.connect(ds)
        transaction(db) { SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES) }
        dropLegacyNameIndex(ds)
        return db
    }

    /**
     * The sibling-name uniqueness index used to span only (user_id, folder_id,
     * name). Trash now keeps a real deleted_at while live rows share 0, so the
     * four-column index declared on FilesTable replaces it - and Exposed never
     * drops an index it stopped declaring, so the stale three-column one would
     * keep a trashed file blocking a re-upload of the same name.
     *
     * Best effort and dialect-agnostic: the index is found through JDBC metadata
     * by its exact column set instead of a guessed name, and the DDL runs on a
     * plain auto-commit connection so a rejected DROP (constraint-backed index on
     * PostgreSQL) can fall back without poisoning anything. If both attempts
     * fail the app-level conflict check still answers 409 and only the
     * trashed-name case degrades.
     */
    private fun dropLegacyNameIndex(ds: HikariDataSource) {
        val legacyColumns = setOf("user_id", "folder_id", "name")
        runCatching {
            ds.connection.use { conn ->
                val previousAutoCommit = conn.autoCommit
                // Auto-commit so a rejected DROP (index vs constraint) cannot
                // poison the rest of the work on this connection.
                conn.autoCommit = true
                try {
                    val stale = mutableListOf<String>()
                    conn.metaData.getIndexInfo(null, null, "files", false, false).use { rs ->
                        val uniqueColumns = mutableMapOf<String, MutableSet<String>>()
                        while (rs.next()) {
                            val index = rs.getString("INDEX_NAME") ?: continue
                            val column = rs.getString("COLUMN_NAME") ?: continue
                            if (rs.getBoolean("NON_UNIQUE")) continue
                            uniqueColumns.getOrPut(index) { mutableSetOf() }.add(column.lowercase())
                        }
                        stale += uniqueColumns.filter { it.value == legacyColumns }.keys
                    }
                    // Identifiers come from database metadata, never from a
                    // request; still require the plain shape before putting one
                    // into a DDL statement.
                    stale.filter { Regex("^[A-Za-z0-9_]{1,63}$").matches(it) }.forEach { index ->
                        runCatching { conn.createStatement().use { it.execute("DROP INDEX IF EXISTS $index") } }
                            .recoverCatching { conn.createStatement().use { it.execute("ALTER TABLE files DROP CONSTRAINT IF EXISTS $index") } }
                            .onFailure { log.warn("legacy files name index {} is still in place", index) }
                    }
                } finally {
                    conn.autoCommit = previousAutoCommit
                }
            }
        }.onFailure { log.warn("could not inspect files indexes for the legacy name index", it) }
    }
}
