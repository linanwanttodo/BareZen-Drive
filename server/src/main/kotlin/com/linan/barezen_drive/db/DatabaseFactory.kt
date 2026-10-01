package com.linan.barezen_drive.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object DatabaseFactory {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    val ALL_TABLES = arrayOf(UsersTable, RefreshTokensTable, FoldersTable, FilesTable, UploadSessionsTable, UploadChunksTable, FileVersionsTable, BlobDeleteQueueTable, WebdavTokensTable, ShareLinksTable, SettingsTable)

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

    fun connect(
        jdbcUrl: String,
        user: String,
        password: String,
        poolSize: Int = 5,
        connectionTimeoutMs: Long = 30_000,
        leakDetectionMs: Long = 0,
    ): Database {
        runCatching { pool?.close() }
        val ds = HikariDataSource(poolConfig(jdbcUrl, user, password, poolSize, connectionTimeoutMs, leakDetectionMs))
        log.info(
            "connection pool: {} connections, {} ms to wait for one, leak detection {}",
            poolSize, connectionTimeoutMs, if (leakDetectionMs > 0) "${leakDetectionMs} ms" else "off",
        )
        pool = ds
        db = Database.connect(ds)
        transaction(db) { SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES) }
        dropLegacyNameIndex(ds)
        createRootNameIndexes(ds)
        createAlbumSortIndex(ds)
        return db
    }

    /**
     * The pool settings, in one place so the numbers are inspectable.
     *
     * The pool is the whole server's concurrency budget: every request that
     * touches the database waits for one of these, and the pool is only five
     * wide by default because a 1C/1G box cannot serve more Postgres backends
     * than it can schedule. What is worth making configurable is the failure
     * mode: the wait for a connection used to be Hikari's 30 s default, which is
     * also how long a caller sat there when the pool was exhausted by something
     * that leaked or held a connection across slow work - a shorter, explicit
     * timeout turns that into a visible error instead of a minute of silence.
     *
     * `isAutoCommit = false` is what Exposed's transaction management expects;
     * it must not be turned on.
     */
    internal fun poolConfig(
        jdbcUrl: String,
        user: String,
        password: String,
        poolSize: Int,
        connectionTimeoutMs: Long,
        leakDetectionMs: Long,
    ): HikariConfig = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.username = user
        this.password = password
        maximumPoolSize = poolSize.coerceAtLeast(1)
        this.connectionTimeout = connectionTimeoutMs.coerceAtLeast(250)
        // Hikari only accepts 0 (off) or at least 2 s, and logs a warning for
        // anything smaller.
        this.leakDetectionThreshold = if (leakDetectionMs > 0) leakDetectionMs.coerceAtLeast(2_000) else 0
        isAutoCommit = false
    }

    /**
     * Partial unique indexes for root-level sibling names. The full indexes
     * declared on FoldersTable/FilesTable include parent_id/folder_id, and
     * PostgreSQL's default NULLS DISTINCT makes rows whose parent is NULL
     * never collide - so at the root the concurrency backstop behind
     * mapNameConflict was dead and two racing creates could both land,
     * producing duplicate sibling names. These partial indexes cover exactly
     * the NULL-parent rows (live files only: deleted_at = 0, mirroring the
     * four-column index's trash carve-out).
     *
     * PostgreSQL only: H2 (the test dialect) has no partial indexes, and the
     * app-level pre-check answers 409 for every sequential case anyway.
     * Exposed's createMissingTablesAndColumns backfills plain column indexes
     * but knows nothing about partial ones, so this runs on every boot; IF NOT
     * EXISTS keeps it idempotent. If existing root rows already
     * hold duplicates the create fails - logged with guidance instead of
     * silently mutating data.
     */
    private fun createRootNameIndexes(ds: HikariDataSource) {
        val ddl = listOf(
            "folders" to
                "CREATE UNIQUE INDEX IF NOT EXISTS folders_root_name_uidx " +
                "ON folders (user_id, name) WHERE parent_id IS NULL",
            "files" to
                "CREATE UNIQUE INDEX IF NOT EXISTS files_root_name_uidx " +
                "ON files (user_id, name) WHERE folder_id IS NULL AND deleted_at = 0",
        )
        runCatching {
            ds.connection.use { conn ->
                val product = conn.metaData.databaseProductName.orEmpty()
                if (!product.contains("PostgreSQL", ignoreCase = true)) return
                conn.autoCommit = true
                ddl.forEach { (table, statement) ->
                    runCatching { conn.createStatement().use { it.execute(statement) } }
                        .onFailure {
                            log.warn(
                                "could not create the root-level name index on {} - most likely " +
                                    "existing root rows already contain duplicate names; deduplicate " +
                                    "them and the next boot will retry",
                                table,
                                it,
                            )
                        }
                }
            }
        }.onFailure { log.warn("could not inspect the database for root-level name indexes", it) }
    }

    /**
     * Expression index for the album ordering key.
     *
     * The album endpoint orders by COALESCE(taken_at, updated_at) - the moment
     * a photo was taken, falling back to when it was uploaded - which no plain
     * column index can serve: every page sorted the user's entire live set,
     * once more per page as the user scrolled. The cursor comparison uses the
     * same expression, so one index serves both the ORDER BY and the keyset
     * seek.
     *
     * PostgreSQL only (H2 has no expression indexes and the test data is
     * tiny); declared here rather than on FilesTable because Exposed's index
     * builder only takes column references. IF NOT EXISTS keeps it idempotent.
     */
    private fun createAlbumSortIndex(ds: HikariDataSource) {
        // user_id leads: the album query always scopes to one user, and an
        // index that started at the expression would have to be walked whole
        // to find that user's rows.
        val statement = "CREATE INDEX IF NOT EXISTS files_album_sort_idx ON files " +
            "(user_id, (COALESCE(taken_at, updated_at)) DESC, id DESC)"
        runCatching {
            ds.connection.use { conn ->
                if (!conn.metaData.databaseProductName.orEmpty().contains("PostgreSQL", ignoreCase = true)) return
                conn.autoCommit = true
                runCatching { conn.createStatement().use { it.execute(statement) } }
                    .onFailure { log.warn("could not create the album sort index", it) }
            }
        }.onFailure { log.warn("could not create the album sort index", it) }
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
