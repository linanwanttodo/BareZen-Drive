package com.linan.barezen_drive.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

// Timestamps are stored as epoch milliseconds (BIGINT) because the Exposed
// javatime extension does not resolve in 0.61.0 with this dependency set.
// Convert to java.time.Instant at DTO mapping boundaries.
object UsersTable : Table("users") {
    val id = uuid("id")
    val username = varchar("username", 32).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    override val primaryKey = PrimaryKey(id)
}

object RefreshTokensTable : Table("refresh_tokens") {
    val id = uuid("id")
    val user = uuid("user_id").references(UsersTable.id)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val expiresAt = long("expires_at")
    val revokedAt = long("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object FoldersTable : Table("folders") {
    val id = uuid("id")
    val user = uuid("user_id").references(UsersTable.id)
    val parent = uuid("parent_id").references(FoldersTable.id).nullable()
    val name = varchar("name", 255)
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex(user, parent, name) }
}

object FilesTable : Table("files") {
    val id = uuid("id")
    val user = uuid("user_id").references(UsersTable.id)
    val folder = uuid("folder_id").references(FoldersTable.id).nullable()
    val name = varchar("name", 255)
    val size = long("size")
    val mimeType = varchar("mime_type", 255).nullable()
    val sha256 = varchar("sha256", 64)
    val storageKey = varchar("storage_key", 512)
    // SQL-level default (not clientDefault): createMissingTablesAndColumns ALTERs
    // existing databases, and a NOT NULL add without DEFAULT fails on any table
    // that already holds rows (PostgreSQL rejects it outright).
    val hasThumbnail = bool("has_thumbnail").default(false)
    // Capture time from the source device (EXIF/DATE_TAKEN); null for
    // uploads without that info. The album timeline sorts on this when set,
    // so re-uploads never shuffle the gallery order.
    val takenAt = long("taken_at").nullable()
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(user, folder, name)
        // Non-unique index for refcount/dedup lookups by content hash.
        index(customIndexName = "files_sha256_idx", isUnique = false, sha256)
    }
}

object UploadSessionsTable : Table("upload_sessions") {
    val id = uuid("id")
    val user = uuid("user_id").references(UsersTable.id)
    val folder = uuid("folder_id").references(FoldersTable.id).nullable()
    val name = varchar("name", 255)
    val size = long("size")
    val mimeType = varchar("mime_type", 255).nullable()
    val chunkSize = long("chunk_size")
    val clientSha256 = varchar("client_sha256", 64).nullable()
    val takenAt = long("taken_at").nullable()
    val status = varchar("status", 16).clientDefault { "open" }
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(id)
}

object UploadChunksTable : Table("upload_chunks") {
    val session = uuid("session_id").references(UploadSessionsTable.id)
    val chunkIndex = integer("chunk_index")
    val size = long("size")
    override val primaryKey = PrimaryKey(session, chunkIndex)
}

// Read-only share links. The token is 32 random bytes shown once in the create
// response; only its SHA-256 is stored so a DB leak does not expose valid links.
// FK cascade keeps rows consistent when the shared file/folder is deleted.
object ShareLinksTable : Table("share_links") {
    val id = uuid("id")
    val user = uuid("user_id").references(UsersTable.id)
    val file = uuid("file_id").references(FilesTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val folder = uuid("folder_id").references(FoldersTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val expiresAt = long("expires_at").nullable()
    val revokedAt = long("revoked_at").nullable()
    // Counters must be SQL-level defaults (not clientDefault): the ALTER for
    // existing databases needs DEFAULT 0 to backfill rows instead of failing.
    val viewCount = long("view_count").default(0)
    val downloadCount = long("download_count").default(0)
    override val primaryKey = PrimaryKey(id)
    init {
        // Active (non-revoked) links per target for "one active share" queries.
        index(customIndexName = "share_target_idx", isUnique = false, file, folder)
    }
}

// Owner-managed server settings as key/value rows. New booleans are added here
// instead of new tables; the row appears on first write, so a fresh server
// starts on defaults without a migration step.
object SettingsTable : Table("settings") {
    val key = varchar("key", 64)
    val value = varchar("value", 255)
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }
    override val primaryKey = PrimaryKey(key)
}
