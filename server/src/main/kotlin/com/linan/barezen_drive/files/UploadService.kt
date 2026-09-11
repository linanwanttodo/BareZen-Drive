package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.core.dto.ErrorCodes
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitRequest
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.UploadChunksTable
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.storage.StorageProvider
import com.linan.barezen_drive.storage.thumbKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

object UploadService {
    private val MIB = 1024L * 1024
    val DEFAULT_CHUNK = 5L * MIB
    val MIN_CHUNK = MIB
    val MAX_CHUNK = 20L * MIB
    private const val SESSION_TTL_MILLIS = 24L * 3600 * 1000

    private fun expectedChunks(size: Long, chunkSize: Long) = ((size + chunkSize - 1) / chunkSize).toInt()

    /** Folders and files share one sibling namespace: a clash on either side is a conflict. */
    private fun siblingNameTaken(userId: UUID, parent: UUID?, name: String): Boolean {
        val folderHit = if (parent == null) {
            FoldersTable.selectAll().where { (FoldersTable.user eq userId) and FoldersTable.parent.isNull() and (FoldersTable.name eq name) }.any()
        } else {
            FoldersTable.selectAll().where { (FoldersTable.user eq userId) and (FoldersTable.parent eq parent) and (FoldersTable.name eq name) }.any()
        }
        val fileHit = if (parent == null) {
            FilesTable.selectAll().where { (FilesTable.user eq userId) and FilesTable.folder.isNull() and (FilesTable.name eq name) }.any()
        } else {
            FilesTable.selectAll().where { (FilesTable.user eq userId) and (FilesTable.folder eq parent) and (FilesTable.name eq name) }.any()
        }
        return folderHit || fileHit
    }

    private fun openSession(userId: UUID, sessionId: UUID): ResultRow {
        val row = transaction(DatabaseFactory.db) {
            UploadSessionsTable.selectAll().where { (UploadSessionsTable.id eq sessionId) and (UploadSessionsTable.user eq userId) }.singleOrNull()
        } ?: throw ApiException.notFound("上传会话不存在")
        when (row[UploadSessionsTable.status]) {
            "completed" -> throw ApiException.conflict(ErrorCodes.SESSION_COMPLETED, "会话已完成")
            "aborted" -> throw ApiException.notFound("会话已取消")
        }
        if (row[UploadSessionsTable.expiresAt] < System.currentTimeMillis()) {
            throw ApiException.conflict(ErrorCodes.SESSION_EXPIRED, "会话已过期")
        }
        return row
    }

    suspend fun initUpload(userId: UUID, req: UploadInitRequest, storage: StorageProvider): UploadInitResponse {
        if (req.name.isBlank() || req.name.contains('/') || req.name.length > 255) throw ApiException.badRequest("文件名非法")
        if (req.size < 0) throw ApiException.badRequest("size 非法")
        val parent: UUID? = req.folderId?.let {
            val pid = it.toUuidOrBadRequest()
            transaction(DatabaseFactory.db) { FoldersTable.selectAll().where { (FoldersTable.id eq pid) and (FoldersTable.user eq userId) }.singleOrNull() }
                ?: throw ApiException.notFound("目标文件夹不存在")
            pid
        }
        val chunkSize = (req.chunkSize ?: DEFAULT_CHUNK).coerceIn(MIN_CHUNK, MAX_CHUNK)
        val sha = req.sha256?.lowercase()?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) }

        // Instant upload: the blob already exists by content hash and the name is free.
        if (sha != null) {
            val key = storage.blobKey(sha)
            if (storage.exists(key)) {
                // A cover may already exist for this content (previous upload of the
                // same bytes); the new row inherits the flag without any client work.
                val hasThumb = storage.exists(thumbKey(sha))
                val created = transaction(DatabaseFactory.db) {
                    if (siblingNameTaken(userId, parent, req.name)) null else {
                        val id = UUID.randomUUID()
                        FilesTable.insert {
                            it[FilesTable.id] = id; it[user] = userId; it[folder] = parent
                            it[FilesTable.name] = req.name; it[FilesTable.size] = req.size
                            it[mimeType] = req.mimeType; it[FilesTable.sha256] = sha; it[storageKey] = key
                            it[hasThumbnail] = hasThumb
                            it[takenAt] = req.takenAt
                        }
                        FilesTable.selectAll().where { FilesTable.id eq id }.single().toFileDto()
                    }
                }
                if (created != null) return UploadInitResponse("", chunkSize, emptyList(), instantUpload = true, file = created)
            }
        }

        // Resume match: open session with the same user, folder, name and size.
        val existing = transaction(DatabaseFactory.db) {
            (if (parent == null)
                UploadSessionsTable.selectAll().where { (UploadSessionsTable.user eq userId) and UploadSessionsTable.folder.isNull() and (UploadSessionsTable.name eq req.name) and (UploadSessionsTable.size eq req.size) and (UploadSessionsTable.status eq "open") }
            else
                UploadSessionsTable.selectAll().where { (UploadSessionsTable.user eq userId) and (UploadSessionsTable.folder eq parent) and (UploadSessionsTable.name eq req.name) and (UploadSessionsTable.size eq req.size) and (UploadSessionsTable.status eq "open") }
            ).firstOrNull()
        }
        val sessionId = existing?.get(UploadSessionsTable.id) ?: UUID.randomUUID().also { sid ->
            transaction(DatabaseFactory.db) {
                UploadSessionsTable.insert {
                    it[UploadSessionsTable.id] = sid; it[user] = userId; it[folder] = parent
                    it[UploadSessionsTable.name] = req.name; it[UploadSessionsTable.size] = req.size
                    it[mimeType] = req.mimeType; it[UploadSessionsTable.chunkSize] = chunkSize
                    it[clientSha256] = sha; it[expiresAt] = System.currentTimeMillis() + SESSION_TTL_MILLIS
                    it[takenAt] = req.takenAt
                }
            }
        }
        if (req.takenAt != null && existing != null && existing[UploadSessionsTable.takenAt] == null) {
            transaction(DatabaseFactory.db) {
                UploadSessionsTable.update({ UploadSessionsTable.id eq sessionId }) { it[takenAt] = req.takenAt }
            }
        }
        val received = transaction(DatabaseFactory.db) {
            UploadChunksTable.selectAll().where { UploadChunksTable.session eq sessionId }.map { it[UploadChunksTable.chunkIndex] }
        }
        return UploadInitResponse(sessionId.toString(), chunkSize, received.sorted())
    }

    /**
     * Streams the chunk body straight to a temp file on disk instead of
     * buffering it in the heap: peak memory per upload is the copy buffer
     * regardless of chunk size or concurrency. Size and the optional
     * X-Chunk-Sha256 are verified while streaming; a mismatch deletes the
     * partial file and fails the request.
     */
    suspend fun putChunk(userId: UUID, sessionId: UUID, index: Int, body: ByteReadChannel, chunkSha: String?, storage: StorageProvider) {
        val row = openSession(userId, sessionId)
        val chunkSize = row[UploadSessionsTable.chunkSize]
        val total = row[UploadSessionsTable.size]
        val expected = expectedChunks(total, chunkSize)
        if (index !in 0 until expected) throw ApiException.badRequest("chunk index 越界", ErrorCodes.CHUNK_INVALID)
        val expectedSize = if (index == expected - 1) total - chunkSize * (expected - 1) else chunkSize

        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val dir = storage.tmpDir.resolve(sessionId.toString()).toFile().apply { mkdirs() }
        val part = File(dir, "$index.part")
        try {
            withContext(Dispatchers.IO) {
                FileOutputStream(part).use { out ->
                    val channel = out.channel
                    val buf = java.nio.ByteBuffer.allocate(64 * 1024)
                    while (true) {
                        buf.clear()
                        val n = body.readAvailable(buf)
                        if (n < 0) break
                        if (n > 0) {
                            written += n
                            // Hard stop past the expected size: a lying client cannot
                            // fill the disk through one chunk slot.
                            if (written > expectedSize) throw ApiException.badRequest("分块大小不符", ErrorCodes.CHUNK_INVALID)
                            digest.update(buf.array(), 0, n)
                            buf.flip()
                            while (buf.hasRemaining()) channel.write(buf)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        if (written != expectedSize) {
            part.delete()
            throw ApiException.badRequest("分块大小不符", ErrorCodes.CHUNK_INVALID)
        }
        if (chunkSha != null && !chunkSha.equals(digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }, ignoreCase = true)) {
            part.delete()
            throw ApiException.badRequest("分块校验不符", ErrorCodes.CHUNK_INVALID)
        }
        transaction(DatabaseFactory.db) {
            val updated = UploadChunksTable.update({ (UploadChunksTable.session eq sessionId) and (UploadChunksTable.chunkIndex eq index) }) {
                it[size] = written
            }
            if (updated == 0) {
                UploadChunksTable.insert {
                    it[UploadChunksTable.session] = sessionId
                    it[UploadChunksTable.chunkIndex] = index
                    it[UploadChunksTable.size] = written
                }
            }
        }
    }

    suspend fun complete(userId: UUID, sessionId: UUID, storage: StorageProvider): UploadCompleteResponse = withContext(Dispatchers.IO) {
        val row = openSession(userId, sessionId)
        val chunkSize = row[UploadSessionsTable.chunkSize]
        val total = row[UploadSessionsTable.size]
        val parent = row[UploadSessionsTable.folder]
        val name = row[UploadSessionsTable.name]
        val mime = row[UploadSessionsTable.mimeType]
        val clientSha = row[UploadSessionsTable.clientSha256]
        val sessionTakenAt = row[UploadSessionsTable.takenAt]
        val expected = expectedChunks(total, chunkSize)
        val have = transaction(DatabaseFactory.db) { UploadChunksTable.selectAll().where { UploadChunksTable.session eq sessionId }.count() }
        if (have != expected.toLong()) throw ApiException.badRequest("缺少分块", ErrorCodes.CHUNK_MISSING)

        val mergeFile = storage.tmpDir.resolve("merge-$sessionId").toFile()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(mergeFile).use { out ->
                val buf = ByteArray(1 shl 16)
                for (i in 0 until expected) {
                    val part = storage.tmpDir.resolve(sessionId.toString()).resolve("$i.part").toFile()
                    FileInputStream(part).use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            // Hex-encode the raw digest. Do NOT hash the digest again - the blob key
            // must equal the content hash for dedup/instant upload to match.
            val sha = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            if (clientSha != null && !clientSha.equals(sha, ignoreCase = true)) {
                throw ApiException.badRequest("整体哈希与 init 不符", ErrorCodes.CHUNK_INVALID)
            }
            val key = storage.blobKey(sha)
            if (!storage.exists(key)) storage.put(key, FileInputStream(mergeFile).toByteReadChannel())
            val hasThumb = storage.exists(thumbKey(sha))
            val fileDto = transaction(DatabaseFactory.db) {
                if (siblingNameTaken(userId, parent, name)) throw ApiException.conflict(ErrorCodes.NAME_CONFLICT, "同级已存在同名文件夹或文件")
                val id = UUID.randomUUID()
                FilesTable.insert {
                    it[FilesTable.id] = id; it[user] = userId; it[folder] = parent
                    it[FilesTable.name] = name; it[FilesTable.size] = total
                    it[mimeType] = mime; it[FilesTable.sha256] = sha; it[storageKey] = key
                    it[hasThumbnail] = hasThumb
                    it[takenAt] = sessionTakenAt
                }
                UploadSessionsTable.update({ UploadSessionsTable.id eq sessionId }) { it[status] = "completed" }
                FilesTable.selectAll().where { FilesTable.id eq id }.single().toFileDto()
            }
            cleanupSessionDir(storage, sessionId)
            UploadCompleteResponse(fileDto)
        } finally {
            mergeFile.delete()
        }
    }

    suspend fun abort(userId: UUID, sessionId: UUID, storage: StorageProvider) {
        openSession(userId, sessionId)
        transaction(DatabaseFactory.db) { UploadSessionsTable.update({ UploadSessionsTable.id eq sessionId }) { it[status] = "aborted" } }
        withContext(Dispatchers.IO) { cleanupSessionDir(storage, sessionId) }
    }

    suspend fun cleanupExpired(storage: StorageProvider) {
        // The cleanup coroutine starts before module() connects; skip the very first
        // run if the database is not wired yet.
        if (!DatabaseFactory.connected) return
        val now = System.currentTimeMillis()
        val expired = transaction(DatabaseFactory.db) {
            UploadSessionsTable.selectAll()
                .where { (UploadSessionsTable.status eq "open") and (UploadSessionsTable.expiresAt less now) }
                .map { it[UploadSessionsTable.id] }
        }
        withContext(Dispatchers.IO) {
            expired.forEach { sid ->
                // Chunk rows reference the session (FK), so they go first.
                transaction(DatabaseFactory.db) {
                    UploadChunksTable.deleteWhere { UploadChunksTable.session eq sid }
                    UploadSessionsTable.deleteWhere { UploadSessionsTable.id eq sid }
                }
                cleanupSessionDir(storage, sid)
            }
        }
    }

    private fun cleanupSessionDir(storage: StorageProvider, sessionId: UUID) {
        storage.tmpDir.resolve(sessionId.toString()).toFile().deleteRecursively()
    }
}
