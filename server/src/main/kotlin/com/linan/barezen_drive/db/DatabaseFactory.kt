package com.linan.barezen_drive.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    val ALL_TABLES = arrayOf(UsersTable, RefreshTokensTable, FoldersTable, FilesTable, UploadSessionsTable, UploadChunksTable, ShareLinksTable)

    // Exposed 0.61 registers the manager into the CALLING thread's ThreadLocal, so the
    // no-arg transaction {} form fails on other threads (Netty event loops). Every call
    // site must therefore pass this instance explicitly: transaction(DatabaseFactory.db).
    lateinit var db: Database
        private set

    /** True once connect() has run on this process. */
    val connected: Boolean get() = ::db.isInitialized

    fun connect(jdbcUrl: String, user: String, password: String): Database {
        val ds = HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password
            maximumPoolSize = 5
            isAutoCommit = false
        })
        db = Database.connect(ds)
        transaction(db) { SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES) }
        return db
    }
}
