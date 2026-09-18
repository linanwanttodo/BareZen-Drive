package com.linan.barezen_drive.sync

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.linan.barezen_drive.AndroidContext

/** A row of sync_items shaped for the queue. */
data class SyncItem(
    val uri: String,
    val bucket: String?,
    val name: String,
    val size: Long,
    val dateModified: Long,
    val dateTaken: Long?,
    val isPhoto: Boolean,
    val state: SyncState,
    val attempts: Int,
    val hashCache: String?,
)

/**
 * Persistent sync queue backed by the framework SQLite (no Room/KSP: the
 * annotation processor is a build-surface change this project deliberately
 * avoids). One row per content Uri; the primary key is the Uri so a rescan is
 * an idempotent upsert, and `date_modified` + `size` let the reconciler tell an
 * edited photo from an untouched one.
 */
object SyncDb {
    private const val DB_NAME = "barezen_sync.db"
    private const val DB_VERSION = 1
    private const val TABLE = "sync_items"
    private const val BUCKETS = "sync_buckets"

    // The pre-SQLite implementation stored the synced fingerprints as a String
    // Set in this prefs file; the first sync-db open migrates them to DONE rows
    // so existing installs do not re-upload their whole library.
    private const val LEGACY_PREFS = "barezen_sync"
    private const val LEGACY_KEY_SYNCED = "synced"
    private const val MIGRATION_FLAG = "synced_migrated_v1"

    private val helper = object : SQLiteOpenHelper(AndroidContext.app, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "uri TEXT PRIMARY KEY," +
                    "bucket TEXT," +
                    "name TEXT NOT NULL," +
                    "size INTEGER NOT NULL," +
                    "date_modified INTEGER NOT NULL," +
                    "date_taken INTEGER," +
                    "is_photo INTEGER NOT NULL DEFAULT 1," +
                    "state TEXT NOT NULL," +
                    "attempts INTEGER NOT NULL DEFAULT 0," +
                    "last_error TEXT," +
                    "hash_cache TEXT," +
                    "server_file_id TEXT," +
                    "uploaded_at INTEGER NOT NULL DEFAULT 0" +
                    ")",
            )
            db.execSQL("CREATE INDEX idx_sync_state ON $TABLE (state, is_photo)")
            db.execSQL("CREATE TABLE $BUCKETS (bucket TEXT PRIMARY KEY, included INTEGER NOT NULL DEFAULT 1)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Single version for now; future migrations append ALTERs here.
        }
    }

    private fun db(): SQLiteDatabase = helper.writableDatabase

    /** One-time import of the legacy prefs set into DONE rows. Safe to call on
     *  every worker start: it self-disables via a flag once it has run. */
    fun migrateLegacyOnce() {
        val prefs = AndroidContext.app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MIGRATION_FLAG, false)) return
        val legacy = prefs.getStringSet(LEGACY_KEY_SYNCED, emptySet()).orEmpty()
        db().beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (fp in legacy) {
                // Legacy fingerprint is "<uri>|<dateModifiedSeconds>".
                val idx = fp.lastIndexOf('|')
                if (idx <= 0) continue
                val uri = fp.substring(0, idx)
                val date = fp.substring(idx + 1).toLongOrNull() ?: 0L
                val cv = ContentValues().apply {
                    put("uri", uri)
                    put("name", uri.substringAfterLast('/'))
                    // The legacy fingerprint carried no size. A placeholder 0
                    // would make isStale flag every row on the first scan and
                    // reset the whole library to PENDING; the sentinel marks
                    // the size as unknown instead (see SyncPolicy.SIZE_UNKNOWN),
                    // and the first full scan writes the real value over it.
                    put("size", SyncPolicy.SIZE_UNKNOWN)
                    put("date_modified", date)
                    put("state", SyncState.DONE.key)
                    put("attempts", 0)
                    put("uploaded_at", now)
                }
                db().insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db().setTransactionSuccessful()
        } finally {
            db().endTransaction()
        }
        prefs.edit().clear().putBoolean(MIGRATION_FLAG, true).apply()
    }

    /** A worker killed mid-upload leaves UPLOADING rows; reset them so the next
     *  pass retries instead of stranding items forever. */
    fun resetUploadingToPending() {
        db().execSQL(
            "UPDATE $TABLE SET state='${SyncState.PENDING.key}' WHERE state='${SyncState.UPLOADING.key}'",
        )
    }

    /**
     * Fold a MediaStore scan into the queue.
     *
     * - a brand-new Uri becomes PENDING;
     * - a DONE row whose size or date_modified changed (an edited photo) goes
     *   back to PENDING and drops its cached hash;
     * - a FAILED row whose retry budget is exhausted goes back to PENDING: a
     *   fresh scan is the one heartbeat this queue has, and without this the
     *   photo would sit in FAILED forever - invisible to the due queries,
     *   uncounted, never retried even after the network that failed it healed;
     * - rows already PENDING/UPLOADING keep their state (we only refresh
     *   the descriptive columns), so an in-flight or backoff-delayed item is
     *   never resurrected by a concurrent scan.
     */
    fun upsertScanned(items: List<ScannedMedia>) {
        if (items.isEmpty()) return
        val d = db()
        d.beginTransaction()
        try {
            // Existing rows come back in chunked IN(...) queries instead of one
            // SELECT per item: a 50k-photo library would otherwise issue two
            // statements per photo. 500 placeholders stays comfortably below
            // SQLite's host-parameter limit.
            val existing = HashMap<String, SyncRow>()
            items.map { it.uri }.distinct().chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                queryListOn(
                    d,
                    "SELECT * FROM $TABLE WHERE uri IN ($placeholders)",
                    chunk.toTypedArray(),
                    ::toRow,
                ).forEach { existing[it.uri] = it }
            }
            for (m in items) {
                val existingRow = existing[m.uri]
                if (existingRow == null) {
                    if (isBucketExcluded(m.bucket)) continue
                    d.insert(TABLE, null, valuesOf(m, SyncState.PENDING, 0))
                } else {
                    val budgetOver = existingRow.state == SyncState.FAILED &&
                        existingRow.attempts >= SyncPolicy.MAX_ATTEMPTS
                    val stale = SyncPolicy.isStale(
                        state = existingRow.state,
                        storedSize = existingRow.size,
                        storedDateModified = existingRow.dateModified,
                        scannedSize = m.size,
                        scannedDateModified = m.dateModified,
                    )
                    val retry = stale || budgetOver
                    val newState = if (retry) SyncState.PENDING else existingRow.state
                    val cv = valuesOf(m, newState, if (retry) 0 else existingRow.attempts)
                    if (retry) {
                        cv.putNull("hash_cache")
                        cv.putNull("server_file_id")
                    } else {
                        // Preserve the queue-owned columns for a live row.
                        existingRow.hashCache?.let { cv.put("hash_cache", it) }
                        existingRow.serverFileId?.let { cv.put("server_file_id", it) }
                        cv.put("state", existingRow.state.key)
                        cv.put("attempts", existingRow.attempts)
                        cv.put("uploaded_at", existingRow.uploadedAt)
                    }
                    d.update(TABLE, cv, "uri=?", arrayOf(m.uri))
                }
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    // Rows in an album the user excluded are kept in the table (dropping them
    // would lose their state if the album is switched back on) but filtered out
    // of every read. One shared fragment means the drain loop, the pending
    // counter and the failure counter can never disagree about what is eligible.
    private const val NOT_EXCLUDED =
        "NOT EXISTS (SELECT 1 FROM $BUCKETS b WHERE b.bucket = ${TABLE}.bucket AND b.included = 0)"

    private const val DUE_STATE =
        "(${TABLE}.state=? OR (${TABLE}.state=? AND ${TABLE}.attempts < ?))"

    /** Rows ready to upload this pass: PENDING, or FAILED still under the retry
     *  ceiling, in albums the user did not exclude. Photos come first, then by
     *  oldest modification time. */
    fun due(maxAttempts: Int = SyncPolicy.MAX_ATTEMPTS): List<SyncItem> =
        queryList(
            "SELECT * FROM $TABLE WHERE $NOT_EXCLUDED AND $DUE_STATE " +
                "ORDER BY ${SyncPolicy.dueOrder()}",
            arrayOf(SyncState.PENDING.key, SyncState.FAILED.key, maxAttempts.toString()),
        )

    /** Rows the next pass would actually try, for the pending counter on the
     *  status card. Excluded albums are not counted: reporting "42 to back up"
     *  while the user has deliberately switched 40 of them off is a lie. */
    fun dueCount(maxAttempts: Int = SyncPolicy.MAX_ATTEMPTS): Int = countQuery(
        "SELECT COUNT(*) FROM $TABLE WHERE $NOT_EXCLUDED AND $DUE_STATE",
        arrayOf(SyncState.PENDING.key, SyncState.FAILED.key, maxAttempts.toString()),
    )

    /** FAILED rows in eligible albums, whatever their retry budget says. The
     *  status card shows this as a subset of the pending count, so a failure
     *  must never make the number silently vanish once attempts run out - the
     *  photo is still there, still un-backed-up, and the count must say so. */
    fun failedCount(): Int = countQuery(
        "SELECT COUNT(*) FROM $TABLE WHERE $NOT_EXCLUDED AND ${TABLE}.state=?",
        arrayOf(SyncState.FAILED.key),
    )

    /** Rows a lane has claimed right now. Read by the status card so "N uploading"
     *  reflects the live pass instead of a second piece of bookkeeping. */
    fun uploadingCount(): Int =
        countQuery("SELECT COUNT(*) FROM $TABLE WHERE ${TABLE}.state=?", arrayOf(SyncState.UPLOADING.key))

    fun markUploading(uri: String) {
        db().execSQL(
            "UPDATE $TABLE SET state='${SyncState.UPLOADING.key}' WHERE uri=?",
            arrayOf(uri),
        )
    }

    fun markDone(uri: String, serverFileId: String?, hash: String?) {
        db().execSQL(
            "UPDATE $TABLE SET state=?, attempts=0, last_error=NULL, server_file_id=?, hash_cache=?, uploaded_at=? WHERE uri=?",
            arrayOf(SyncState.DONE.key, serverFileId, hash, System.currentTimeMillis().toString(), uri),
        )
    }

    fun markFailed(uri: String, error: String?, hash: String?) {
        // Read attempts, bump, and cap; a permanent read error keeps the cached
        // hash so a retry does not re-hash an unchanged file.
        val cur = queryOne(db(), uri)
        val attempts = (cur?.attempts ?: 0) + 1
        db().execSQL(
            "UPDATE $TABLE SET state=?, attempts=?, last_error=?, hash_cache=? WHERE uri=?",
            arrayOf(SyncState.FAILED.key, attempts.toString(), error, hash, uri),
        )
    }

    /** Reconcile: drop queue rows whose content Uri vanished from the device
     *  (the user deleted the photo, or uninstalled the producing app). */
    fun pruneMissing(presentUris: Set<String>) {
        val d = db()
        val all = queryList("SELECT uri FROM $TABLE", null).map { it.uri }
        val doomed = all.filter { it !in presentUris }
        if (doomed.isEmpty()) return
        d.beginTransaction()
        try {
            for (uri in doomed) d.delete(TABLE, "uri=?", arrayOf(uri))
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    // ---- Bucket (album) inclusion, feature 6 ----

    fun setBucketIncluded(bucket: String, included: Boolean) {
        val cv = ContentValues().apply {
            put("bucket", bucket)
            put("included", if (included) 1 else 0)
        }
        db().insertWithOnConflict(BUCKETS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun isBucketExcluded(bucket: String?): Boolean {
        if (bucket == null) return false
        val c = db().rawQuery("SELECT included FROM $BUCKETS WHERE bucket=?", arrayOf(bucket))
        return try {
            if (c.moveToFirst()) c.getInt(0) == 0 else false
        } finally {
            c.close()
        }
    }

    fun excludedBucketCount(): Int =
        countQuery("SELECT COUNT(*) FROM $BUCKETS WHERE included=0", null)

    /** Distinct albums seen on the device with a per-album photo/video count. */
    fun listBuckets(): List<BucketInfo> {
        val out = mutableListOf<BucketInfo>()
        val c = db().rawQuery(
            "SELECT bucket, COUNT(*), SUM(is_photo) FROM $TABLE " +
                "WHERE bucket IS NOT NULL GROUP BY bucket ORDER BY bucket COLLATE NOCASE ASC",
            null,
        )
        try {
            while (c.moveToNext()) {
                val bucket = c.getString(0)
                out.add(
                    BucketInfo(
                        bucket = bucket,
                        total = c.getInt(1),
                        included = !isBucketExcluded(bucket),
                    ),
                )
            }
        } finally {
            c.close()
        }
        return out
    }

    private fun valuesOf(m: ScannedMedia, state: SyncState, attempts: Int) = ContentValues().apply {
        put("uri", m.uri)
        put("bucket", m.bucket)
        put("name", m.name)
        put("size", m.size)
        put("date_modified", m.dateModified)
        m.dateTaken?.let { put("date_taken", it) } ?: putNull("date_taken")
        put("is_photo", if (m.isPhoto) 1 else 0)
        put("state", state.key)
        put("attempts", attempts)
    }

    private fun queryOne(d: SQLiteDatabase, uri: String): SyncRow? {
        val list = queryListOn(d, "SELECT * FROM $TABLE WHERE uri=?", arrayOf(uri), ::toRow)
        return list.firstOrNull()
    }

    private fun queryList(sql: String, args: Array<String>?): List<SyncItem> =
        queryListOn(db(), sql, args, ::toItem)

    private fun <T> queryListOn(d: SQLiteDatabase, sql: String, args: Array<String>?, map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        val c = d.rawQuery(sql, args)
        try {
            while (c.moveToNext()) out.add(map(c))
        } finally {
            c.close()
        }
        return out
    }

    private fun countQuery(sql: String, args: Array<String>?): Int {
        val c = db().rawQuery(sql, args)
        return try {
            if (c.moveToFirst()) c.getInt(0) else 0
        } finally {
            c.close()
        }
    }

    private fun toItem(c: Cursor): SyncItem {
        val r = toRow(c)
        return SyncItem(r.uri, r.bucket, r.name, r.size, r.dateModified, r.dateTaken, r.isPhoto, r.state, r.attempts, r.hashCache)
    }

    private fun toRow(c: Cursor): SyncRow = SyncRow(
        uri = c.str("uri") ?: "",
        bucket = c.strOrNull("bucket"),
        name = c.str("name") ?: "photo",
        size = c.getLong(c.getColumnIndexOrThrow("size")),
        dateModified = c.getLong(c.getColumnIndexOrThrow("date_modified")),
        dateTaken = c.nullableLong("date_taken"),
        isPhoto = c.getInt(c.getColumnIndexOrThrow("is_photo")) == 1,
        state = SyncState.fromKey(c.str("state") ?: "pending"),
        attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
        lastError = c.strOrNull("last_error"),
        hashCache = c.strOrNull("hash_cache"),
        serverFileId = c.strOrNull("server_file_id"),
        uploadedAt = c.getLong(c.getColumnIndexOrThrow("uploaded_at")),
    )

    private fun Cursor.str(col: String): String? = strOrNull(col)
    // Framework Cursor only exposes getColumnIndex (returns -1 when absent); a
    // nullable helper keeps the mappers below tolerant of trimmed columns.
    private fun Cursor.columnIndexOrNull(col: String): Int? =
        getColumnIndex(col).takeIf { it >= 0 }
    private fun Cursor.strOrNull(col: String): String? {
        val i = columnIndexOrNull(col) ?: return null
        return if (isNull(i)) null else getString(i)
    }
    private fun Cursor.nullableLong(col: String): Long? {
        val i = columnIndexOrNull(col) ?: return null
        return if (isNull(i)) null else getLong(i)
    }
}

/** Full row shape (internal to the DAO). */
private data class SyncRow(
    val uri: String,
    val bucket: String?,
    val name: String,
    val size: Long,
    val dateModified: Long,
    val dateTaken: Long?,
    val isPhoto: Boolean,
    val state: SyncState,
    val attempts: Int,
    val lastError: String?,
    val hashCache: String?,
    val serverFileId: String?,
    val uploadedAt: Long,
)

/** A media row freshly observed from MediaStore (before it enters the queue). */
data class ScannedMedia(
    val uri: String,
    val bucket: String?,
    val name: String,
    val size: Long,
    val dateModified: Long,
    val dateTaken: Long?,
    val isPhoto: Boolean,
)

/** One device album with its queue totals, for the per-album backup settings. */
data class BucketInfo(
    val bucket: String,
    val total: Int,
    val included: Boolean,
)
