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
    // SQL-level default so the ALTER on an existing database backfills 0.
    // Bounds the post-revocation reuse grace window to ONE extra rotation:
    // unlimited grace replays would mint unlimited child tokens for a
    // stolen one (see AuthService.refresh).
    val graceReplays = integer("grace_replays").default(0)
    override val primaryKey = PrimaryKey(id)
    init {
        // The table grows linearly with refresh frequency (30-day TTL) and is
        // only ever queried by "which tokens of this user" and "which are
        // expired" - both were full scans over a table that never shrinks.
        index(customIndexName = "refresh_tokens_user_idx", isUnique = false, user)
        index(customIndexName = "refresh_tokens_expires_idx", isUnique = false, expiresAt)
    }
}

object FoldersTable : Table("folders") {
    val id = uuid("id")
    val user = uuid("user_id").references(UsersTable.id)
    val parent = uuid("parent_id").references(id).nullable()
    val name = varchar("name", 255)
    // Schema columns: written by inserts/defaults, read by SQL - the ORM only
    // needs the definitions to exist, so the unused-symbol check is noise.
    @Suppress("UnusedSymbol")
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    @Suppress("UnusedSymbol")
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(user, parent, name)
        // Subtree walks (album scoping, folder deletion) load a user's whole
        // folder tree and group it by parent.
        index(customIndexName = "folders_user_parent_idx", isUnique = false, user, parent)
        // Public share listing filters by folder alone (no user in the query),
        // and the existing index leads with user_id - so that lookup scanned
        // the whole folders table.
        index(customIndexName = "folders_parent_idx", isUnique = false, parent)
    }
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
    // Gallery flags. All three carry a SQL-level DEFAULT so the ALTER on an
    // existing database backfills rows instead of failing: 0 means "live" /
    // "not archived" and false means "not favorited".
    val isFavorite = bool("is_favorite").default(false)
    val archivedAt = long("archived_at").default(0)
    val deletedAt = long("deleted_at").default(0)
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val updatedAt = long("updated_at").clientDefault { System.currentTimeMillis() }
    override val primaryKey = PrimaryKey(id)
    init {
        // deleted_at is part of the key on purpose: trashed rows hold a real
        // timestamp while live rows share 0, so the index still enforces one
        // live entry per sibling name but lets a deleted name be re-uploaded.
        uniqueIndex(user, folder, name, deletedAt)
        // Non-unique index for refcount/dedup lookups by content hash.
        index(customIndexName = "files_sha256_idx", isUnique = false, sha256)
        // Trash listing and the "live only" filter on every media query.
        index(customIndexName = "files_user_deleted_idx", isUnique = false, user, deletedAt)
        // The refcount sweep asks "does any row still point at this blob?" for
        // every key a delete touches, twice per delete chain. Without this the
        // question is a full scan of the largest table in the schema.
        index(customIndexName = "files_storage_key_idx", isUnique = false, storageKey)
        // Public share listing and folder-subtree deletion filter by folder_id
        // with no user_id in the query, so the four-column name index (which
        // leads with user_id) could not serve either: both were full scans of
        // the largest table in the schema.
        index(customIndexName = "files_folder_idx", isUnique = false, folder)
        // "Recent" is ORDER BY updated_at over this user's live rows, and the
        // (user, deleted_at) index stops at deleted_at - every page of the
        // recent list sorted the user's entire live set.
        index(customIndexName = "files_user_recent_idx", isUnique = false, user, deletedAt, updatedAt)
        // The trash list is a keyset walk ordered by (deleted_at, id) - id is
        // the tiebreaker, without it every row trashed in the same millisecond
        // re-sorts on each page. A plain ascending btree serves the DESC walk
        // by scanning backwards, because user_id is pinned by equality.
        index(customIndexName = "files_user_trash_idx", isUnique = false, user, deletedAt, id)
        // Album ordering is COALESCE(taken_at, updated_at); the expression
        // index for it is PostgreSQL-only and lives in DatabaseFactory.
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
    // SQL-level default: existing databases get ALTERed with DEFAULT FALSE,
    // never a NOT NULL add that fails on populated rows.
    val overwrite = bool("overwrite").default(false)
    val status = varchar("status", 16).clientDefault { "open" }
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(id)
    init {
        // The 6h cleanup job scans this table for expired rows and the resume
        // path looks a session up by (user, folder, name, size) - none of which
        // had an index, so both were full scans of a table that only ever
        // grows between sweeps.
        index(customIndexName = "upload_sessions_expires_idx", isUnique = false, expiresAt)
        index(customIndexName = "upload_sessions_folder_idx", isUnique = false, folder)
        index(customIndexName = "upload_sessions_user_idx", isUnique = false, user)
    }
}

object UploadChunksTable : Table("upload_chunks") {
    val session = uuid("session_id").references(UploadSessionsTable.id)
    val chunkIndex = integer("chunk_index")
    val size = long("size")
    override val primaryKey = PrimaryKey(session, chunkIndex)
}

// Superseded contents of a file, created when an overwrite upload replaces a
// live row. The file row itself always holds the newest content; a version row
// keeps the blob referenced until it is pruned (explicitly, beyond the
// retention cap, or together with the file). Rows go away only through
// explicit deletes or the cascade below, never silently.
/**
 * Blobs that lost their last reference and are waiting out a grace period
 * before their bytes are unlinked.
 *
 * The table is the point of the grace period: the previous code decided "this
 * blob is garbage" and unlinked it in the same breath, which left a window
 * between that decision and the unlink that a concurrent dedup upload could
 * slip through (exists() passes, the reference row commits, the bytes are
 * already gone - data loss). Queuing the key instead turns the decision into
 * something a later pass can re-check, and a key that gained a reference again
 * simply leaves the queue.
 */
object BlobDeleteQueueTable : Table("blob_delete_queue") {
    val storageKey = varchar("storage_key", 512)
    /** When the key was first queued. Never rewritten by a re-queue, or a delete
     *  path that runs twice would restart the clock and leak the blob. */
    val queuedAt = long("queued_at")
    override val primaryKey = PrimaryKey(storageKey)
}

object FileVersionsTable : Table("file_versions") {
    val id = uuid("id")
    val file = uuid("file_id").references(FilesTable.id, onDelete = ReferenceOption.CASCADE)
    // Per-file monotonic counter (1-based). Unique so a revision can never be
    // claimed twice; the overwrite path takes a row lock on the file first
    // (see VersionService.snapshotAndReplace), so there is no loser to retry.
    val revision = long("revision")
    val sha256 = varchar("sha256", 64)
    val storageKey = varchar("storage_key", 512)
    val size = long("size")
    val mimeType = varchar("mime_type", 255).nullable()
    val takenAt = long("taken_at").nullable()
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(file, revision)
        // Same refcount sweep as files: a version row keeps a blob alive, so
        // the question is asked against this table too.
        index(customIndexName = "file_versions_storage_key_idx", isUnique = false, storageKey)
    }
}

// One revocable credential per mounted device (WebDAV).
//
// Deliberately NOT the account password. A mount authenticates on every single
// request, and the account password is a bcrypt hash (cost 10, ~100ms), so
// browsing one folder would spend minutes inside a KDF. Nextcloud documents the
// same trade in the other direction: using the real password for WebDAV carries
// "a significant performance penalty".
//
// So the value is 32 bytes of randomness stored as a SHA-256 hash and looked up
// by exact match. That is safe precisely because it is high entropy: there is no
// guessing surface offline, which is the only thing bcrypt buys here. The token
// authenticates the WebDAV surface and nothing else.
object WebdavTokensTable : Table("webdav_tokens") {
    val id = uuid("id")
    val user = uuid("user_id").references(UsersTable.id)
    /** SHA-256 hex, unique so authentication is a single index lookup. */
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    /** What the user calls this device, so a stray token is recognisable. */
    val label = varchar("label", 64)
    /** A read-only mount cannot delete the library through a stray keystroke. */
    val readOnly = bool("read_only").default(false)
    // SQL-level default, not clientDefault: the ALTER for existing databases
    // needs DEFAULT 0 to backfill rows instead of failing.
    val lastUsedAt = long("last_used_at").default(0)
    val createdAt = long("created_at").clientDefault { System.currentTimeMillis() }
    override val primaryKey = PrimaryKey(id)
    init {
        // The settings list is "all my mounts", which filters on user_id alone.
        index(customIndexName = "webdav_tokens_user_idx", isUnique = false, user)
    }
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
    init {
        // Every share query leads with user_id, including the management list
        // that filters nothing but the owner. Without this the unfiltered list
        // is a full scan of share_links; the fileId/folderId variants already
        // had share_target_idx, but the "all my links" page had no index at all.
        index(customIndexName = "share_links_user_idx", isUnique = false, user)
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
