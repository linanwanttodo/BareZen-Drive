package com.linan.barezen_drive.evidence

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.linan.barezen_drive.AndroidContext
import com.linan.barezen_drive.data.evidence.EvidenceMode
import com.linan.barezen_drive.data.evidence.EvidenceSegmentState
import com.linan.barezen_drive.data.evidence.SegmentKey
import java.io.File

object EvidenceDb {
    private val helper by lazy {
        object : SQLiteOpenHelper(AndroidContext.app, "barezen_evidence.db", null, 2) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE vaults(
                        vault_id TEXT PRIMARY KEY, server_key TEXT NOT NULL, account_id TEXT NOT NULL,
                        manifest BLOB NOT NULL, dek_version INTEGER NOT NULL,
                        evidence_root_folder_id TEXT, vault_folder_id TEXT, manifest_file_id TEXT,
                        created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL)""",
                )
                db.execSQL(
                    """CREATE TABLE sessions(
                        session_id TEXT PRIMARY KEY, vault_id TEXT NOT NULL, server_key TEXT NOT NULL,
                        account_id TEXT NOT NULL, mode TEXT NOT NULL, state TEXT NOT NULL,
                        started_at_ms INTEGER NOT NULL, ended_at_ms INTEGER, cloud_folder_id TEXT,
                        created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL)""",
                )
                db.execSQL(
                    """CREATE TABLE segments(
                        vault_id TEXT NOT NULL, session_id TEXT NOT NULL, segment_number INTEGER NOT NULL,
                        server_key TEXT NOT NULL, account_id TEXT NOT NULL, mode TEXT NOT NULL,
                        name TEXT NOT NULL, plain_path TEXT NOT NULL, cipher_path TEXT NOT NULL,
                        size INTEGER NOT NULL, plain_sha256 TEXT, cipher_sha256 TEXT,
                        started_at_ms INTEGER NOT NULL, ended_at_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL,
                        format_version INTEGER NOT NULL, dek_version INTEGER NOT NULL, state TEXT NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at_ms INTEGER NOT NULL DEFAULT 0,
                        last_error TEXT, cloud_file_id TEXT, created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL,
                        local_deleted INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(vault_id, session_id, segment_number))""",
                )
                db.execSQL(
                    """CREATE TABLE security(
                        server_key TEXT NOT NULL, account_id TEXT NOT NULL,
                        failed_attempts INTEGER NOT NULL DEFAULT 0, cooldown_until_ms INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(server_key, account_id))""",
                )
                db.execSQL("CREATE INDEX segment_queue ON segments(server_key, account_id, state, next_attempt_at_ms)")
                db.execSQL("CREATE INDEX segment_session ON segments(session_id, segment_number)")
                db.execSQL("CREATE INDEX segment_vault ON segments(vault_id)")
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                if (oldVersion < 2) db.execSQL("ALTER TABLE segments ADD COLUMN local_deleted INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun currentVault(vaultId: String): VaultRow? = vaults("vault_id=?", arrayOf(vaultId)).firstOrNull()
    fun currentVaults(serverKey: String, accountId: String): List<VaultRow> =
        vaults("server_key=? AND account_id=?", arrayOf(serverKey, accountId))

    private fun vaults(where: String, args: Array<String>): List<VaultRow> = db.rawQuery(
        "SELECT * FROM vaults WHERE $where ORDER BY updated_at_ms DESC", args,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                VaultRow(
                    vaultId = cursor.getString(0), serverKey = cursor.getString(1), accountId = cursor.getString(2),
                    manifest = cursor.getBlob(3), dekVersion = cursor.getInt(4),
                    evidenceRootFolderId = cursor.getString(5), vaultFolderId = cursor.getString(6),
                    manifestFileId = cursor.getString(7), createdAtMs = cursor.getLong(8), updatedAtMs = cursor.getLong(9),
                ),
            )
        }
    }

    fun allVaults(): List<VaultRow> = vaults("1=1", emptyArray())

    fun insertVault(row: VaultRow) {
        val values = ContentValues().apply {
            put("vault_id", row.vaultId); put("server_key", row.serverKey); put("account_id", row.accountId)
            put("manifest", row.manifest); put("dek_version", row.dekVersion)
            put("evidence_root_folder_id", row.evidenceRootFolderId); put("vault_folder_id", row.vaultFolderId)
            put("manifest_file_id", row.manifestFileId); put("created_at_ms", row.createdAtMs); put("updated_at_ms", row.updatedAtMs)
        }
        db.insertWithOnConflict("vaults", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun replaceManifest(vaultId: String, manifest: ByteArray, dekVersion: Int, updatedAtMs: Long) {
        val values = ContentValues().apply {
            put("manifest", manifest); put("dek_version", dekVersion); put("updated_at_ms", updatedAtMs)
        }
        db.update("vaults", values, "vault_id=?", arrayOf(vaultId))
    }

    fun updateCloudIds(vaultId: String, rootFolderId: String?, vaultFolderId: String?, manifestFileId: String?) {
        val values = ContentValues().apply {
            putNullable("evidence_root_folder_id", rootFolderId)
            putNullable("vault_folder_id", vaultFolderId)
            putNullable("manifest_file_id", manifestFileId)
            put("updated_at_ms", System.currentTimeMillis())
        }
        db.update("vaults", values, "vault_id=?", arrayOf(vaultId))
    }

    fun insertSession(row: SessionRow) {
        val values = ContentValues().apply {
            put("session_id", row.sessionId); put("vault_id", row.vaultId); put("server_key", row.serverKey)
            put("account_id", row.accountId); put("mode", row.mode.name); put("state", row.state)
            put("started_at_ms", row.startedAtMs); put("ended_at_ms", row.endedAtMs); put("cloud_folder_id", row.cloudFolderId)
            put("created_at_ms", row.startedAtMs); put("updated_at_ms", row.startedAtMs)
        }
        db.insertWithOnConflict("sessions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun finishSession(sessionId: String, state: String, endedAtMs: Long, cloudFolderId: String? = null) {
        val values = ContentValues().apply {
            put("state", state)
            putNullable("ended_at_ms", endedAtMs)
            putNullable("cloud_folder_id", cloudFolderId)
            put("updated_at_ms", endedAtMs)
        }
        db.update("sessions", values, "session_id=?", arrayOf(sessionId))
    }

    fun activeSessions(): List<SessionRow> = sessionsWhere("state='RECORDING'", emptyArray())
    fun interruptActiveSessions(endedAtMs: Long): Int {
        val values = ContentValues().apply {
            put("state", "INTERRUPTED"); put("ended_at_ms", endedAtMs); put("updated_at_ms", endedAtMs)
        }
        return db.update("sessions", values, "state=?", arrayOf("RECORDING"))
    }
    fun deleteSessionRows(sessionId: String) {
        db.delete("segments", "session_id=?", arrayOf(sessionId))
        db.delete("sessions", "session_id=?", arrayOf(sessionId))
    }
    fun sessions(vaultId: String): List<SessionRow> = sessionsWhere("vault_id=?", arrayOf(vaultId))

    private fun sessionsWhere(where: String, args: Array<String>): List<SessionRow> = db.rawQuery(
        "SELECT * FROM sessions WHERE $where ORDER BY started_at_ms DESC", args,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                SessionRow(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                    EvidenceMode.valueOf(cursor.getString(4)), cursor.getString(5), cursor.getLong(6),
                    cursor.getLong(7).takeIf { !cursor.isNull(7) }, cursor.getString(8),
                ),
            )
        }
    }

    fun nextSegmentNumber(sessionId: String): Int {
        val number = db.rawQuery(
            "SELECT COALESCE(MAX(segment_number),0)+1 FROM segments WHERE session_id=?", arrayOf(sessionId),
        ).use { it.moveToFirst(); it.getInt(0) }
        require(number <= 999) { "segment limit reached" }
        return number
    }

    fun insertEncryptingSegment(row: SegmentRow) {
        val values = segmentValues(row)
        db.insertWithOnConflict("segments", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun markSegmentPending(key: SegmentKey, size: Long, plainSha256: String, cipherSha256: String, updatedAtMs: Long) {
        val values = ContentValues().apply {
            put("size", size); put("plain_sha256", plainSha256); put("cipher_sha256", cipherSha256)
            put("state", EvidenceSegmentState.PENDING.name); put("updated_at_ms", updatedAtMs)
            put("next_attempt_at_ms", 0); putNull("last_error")
        }
        db.update("segments", values, segmentWhere(key), segmentArgs(key))
    }

    fun dueSegments(serverKey: String, accountId: String, nowMs: Long, limit: Int): List<SegmentRow> {
        val keys = ArrayList<SegmentKey>()
        db.beginTransaction()
        try {
            db.rawQuery(
                """SELECT vault_id,session_id,segment_number FROM segments
                   WHERE server_key=? AND account_id=? AND
                   (state='PENDING' OR (state='FAILED' AND next_attempt_at_ms<=?))
                   ORDER BY created_at_ms LIMIT ?""",
                arrayOf(serverKey, accountId, nowMs.toString(), limit.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) keys += SegmentKey(cursor.getString(0), cursor.getString(1), cursor.getInt(2))
            }
            keys.forEach { key ->
                val values = ContentValues().apply {
                    put("state", EvidenceSegmentState.UPLOADING.name); put("updated_at_ms", nowMs)
                }
                db.update("segments", values, segmentWhere(key), segmentArgs(key))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return keys.mapNotNull { segment(it) }
    }

    fun markSegmentUploaded(key: SegmentKey, cloudFileId: String, updatedAtMs: Long) {
        val values = ContentValues().apply {
            put("state", EvidenceSegmentState.UPLOADED.name); put("cloud_file_id", cloudFileId)
            put("updated_at_ms", updatedAtMs); put("next_attempt_at_ms", 0); putNull("last_error")
        }
        db.update("segments", values, segmentWhere(key), segmentArgs(key))
    }

    fun markSegmentFailed(key: SegmentKey, error: String, nextAttemptAtMs: Long, updatedAtMs: Long) {
        val values = ContentValues().apply {
            put("state", EvidenceSegmentState.FAILED.name); put("last_error", error.take(500))
            put("attempts", attempts(key) + 1); put("next_attempt_at_ms", nextAttemptAtMs); put("updated_at_ms", updatedAtMs)
        }
        db.update("segments", values, segmentWhere(key), segmentArgs(key))
    }

    fun markSegmentInterrupted(key: SegmentKey, error: String, updatedAtMs: Long) {
        val values = ContentValues().apply {
            put("state", EvidenceSegmentState.INTERRUPTED.name); put("last_error", error.take(500)); put("updated_at_ms", updatedAtMs)
        }
        db.update("segments", values, segmentWhere(key), segmentArgs(key))
    }

    fun segment(key: SegmentKey): SegmentRow? = segmentsWhere(segmentWhere(key), segmentArgs(key)).firstOrNull()
    fun segments(sessionId: String): List<SegmentRow> = segmentsWhere("session_id=?", arrayOf(sessionId))
    fun uploadCounts(sessionId: String): EvidenceUploadCounts {
        val rows = segments(sessionId)
        return EvidenceUploadCounts(
            total = rows.size,
            uploaded = rows.count { it.state == EvidenceSegmentState.UPLOADED },
            active = rows.count { it.state == EvidenceSegmentState.ENCRYPTING || it.state == EvidenceSegmentState.PENDING || it.state == EvidenceSegmentState.UPLOADING },
            failed = rows.count { it.state == EvidenceSegmentState.FAILED },
        )
    }

    private fun segmentsWhere(where: String, args: Array<String>): List<SegmentRow> = db.rawQuery(
        "SELECT * FROM segments WHERE $where ORDER BY segment_number", args,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                SegmentRow(
                    vaultId = cursor.getString(0), sessionId = cursor.getString(1), segmentNumber = cursor.getInt(2),
                    serverKey = cursor.getString(3), accountId = cursor.getString(4), mode = EvidenceMode.valueOf(cursor.getString(5)),
                    name = cursor.getString(6), plainPath = cursor.getString(7), cipherPath = cursor.getString(8), size = cursor.getLong(9),
                    plainSha256 = cursor.getString(10), cipherSha256 = cursor.getString(11), startedAtMs = cursor.getLong(12),
                    endedAtMs = cursor.getLong(13), durationMs = cursor.getLong(14), formatVersion = cursor.getInt(15),
                    dekVersion = cursor.getInt(16), state = EvidenceSegmentState.valueOf(cursor.getString(17)), attempts = cursor.getInt(18),
                    nextAttemptAtMs = cursor.getLong(19), lastError = cursor.getString(20), cloudFileId = cursor.getString(21),
                    createdAtMs = cursor.getLong(22), updatedAtMs = cursor.getLong(23),
                    localDeleted = cursor.getInt(24) != 0,
                ),
            )
        }
    }

    fun resetUploadingToPending(): Int {
        val values = ContentValues().apply { put("state", EvidenceSegmentState.PENDING.name) }
        return db.update("segments", values, "state=?", arrayOf(EvidenceSegmentState.UPLOADING.name))
    }

    fun encryptingSegments(): List<SegmentRow> = segmentsWhere("state=?", arrayOf(EvidenceSegmentState.ENCRYPTING.name))

    fun deleteLocalSegment(key: SegmentKey): Boolean {
        val row = segment(key) ?: return false
        if (row.localDeleted || row.state != EvidenceSegmentState.UPLOADED || row.cloudFileId.isNullOrBlank()) return false
        File(row.cipherPath).delete()
        File(row.plainPath).delete()
        val values = ContentValues().apply {
            put("local_deleted", 1); put("updated_at_ms", System.currentTimeMillis())
        }
        return db.update("segments", values, segmentWhere(key), segmentArgs(key)) > 0
    }

    fun deleteVaultLocal(vaultId: String) {
        db.beginTransaction()
        try {
            segmentsWhere("vault_id=?", arrayOf(vaultId)).forEach { row ->
                File(row.cipherPath).delete(); File(row.plainPath).delete()
            }
            db.delete("segments", "vault_id=?", arrayOf(vaultId))
            db.delete("sessions", "vault_id=?", arrayOf(vaultId))
            db.delete("vaults", "vault_id=?", arrayOf(vaultId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun security(serverKey: String, accountId: String): SecurityRow = db.rawQuery(
        "SELECT failed_attempts,cooldown_until_ms FROM security WHERE server_key=? AND account_id=?",
        arrayOf(serverKey, accountId),
    ).use { if (it.moveToFirst()) SecurityRow(it.getInt(0), it.getLong(1)) else SecurityRow(0, 0) }

    fun saveSecurity(serverKey: String, accountId: String, row: SecurityRow) {
        val values = ContentValues().apply {
            put("server_key", serverKey); put("account_id", accountId)
            put("failed_attempts", row.failedAttempts); put("cooldown_until_ms", row.cooldownUntilMs)
        }
        db.insertWithOnConflict("security", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun attempts(key: SegmentKey): Int = db.rawQuery(
        "SELECT attempts FROM segments WHERE vault_id=? AND session_id=? AND segment_number=?", segmentArgs(key),
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun segmentValues(row: SegmentRow) = ContentValues().apply {
        put("vault_id", row.vaultId); put("session_id", row.sessionId); put("segment_number", row.segmentNumber)
        put("server_key", row.serverKey); put("account_id", row.accountId); put("mode", row.mode.name)
        put("name", row.name); put("plain_path", row.plainPath); put("cipher_path", row.cipherPath)
        put("size", row.size)
        putNullable("plain_sha256", row.plainSha256)
        putNullable("cipher_sha256", row.cipherSha256)
        put("started_at_ms", row.startedAtMs); put("ended_at_ms", row.endedAtMs); put("duration_ms", row.durationMs)
        put("format_version", row.formatVersion); put("dek_version", row.dekVersion); put("state", row.state.name)
        put("attempts", row.attempts); put("next_attempt_at_ms", row.nextAttemptAtMs)
        putNullable("last_error", row.lastError)
        putNullable("cloud_file_id", row.cloudFileId)
        put("created_at_ms", row.createdAtMs); put("updated_at_ms", row.updatedAtMs)
        put("local_deleted", if (row.localDeleted) 1 else 0)
    }

    private fun segmentWhere(key: SegmentKey) = "vault_id=? AND session_id=? AND segment_number=?"
    private fun segmentArgs(key: SegmentKey) = arrayOf(key.vaultId, key.sessionId, key.segmentNumber.toString())
    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }
    private fun ContentValues.putNullable(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }
}

data class SecurityRow(val failedAttempts: Int, val cooldownUntilMs: Long)
data class EvidenceUploadCounts(val total: Int, val uploaded: Int, val active: Int, val failed: Int)
