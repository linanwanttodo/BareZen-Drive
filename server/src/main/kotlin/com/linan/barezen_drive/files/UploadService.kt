package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.mapNameConflict
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.core.dto.ErrorCodes
import com.linan.barezen_drive.core.dto.FileVersionDto
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitRequest
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.RefreshTokensTable
import com.linan.barezen_drive.db.UploadChunksTable
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.storage.StorageProvider
import com.linan.barezen_drive.storage.thumbKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

object UploadService {
    private const val MIB = 1024L * 1024
    const val DEFAULT_CHUNK = 5L * MIB
    const val MIN_CHUNK = MIB
    const val MAX_CHUNK = 20L * MIB
    private const val SESSION_TTL_MILLIS = 24L * 3600 * 1000

    /** Fire-and-forget cover generation after a complete; never fails the upload. */
    private val bgScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** MAX_FILE_SIZE from the environment; wired once at module startup. */
    @Volatile
    var maxFileSize: Long = Long.MAX_VALUE

    private fun expectedChunks(size: Long, chunkSize: Long): Int {
        // Clamp instead of overflowing Long arithmetic for absurd stored sizes.
        val chunks = if (size > Long.MAX_VALUE - chunkSize) Long.MAX_VALUE else (size + chunkSize - 1) / chunkSize
        return chunks.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
        if (req.size > maxFileSize) throw ApiException.tooLarge("文件超过服务器上限 ${maxFileSize / MIB} MiB")
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
                val instant = transaction(DatabaseFactory.db) {
                    val clash = FileService.findLiveFile(userId, parent, req.name)
                    when {
                        clash != null && req.overwrite -> {
                            if (clash[FilesTable.sha256] == sha) {
                                // Identical bytes onto the identical name: a no-op
                                // success (nothing to snapshot), still the same row.
                                Triple(clash.toFileDto(), emptyList<String>(), null as FileVersionDto?)
                            } else {
                            // Same dedup rule as a fresh instant row: the size of
                            // the *new* content comes from rows already holding
                            // this blob (or the blob itself), never the client.
                            val storedSize = FilesTable.selectAll().where { FilesTable.storageKey eq key }
                                .firstOrNull()?.get(FilesTable.size)
                                ?: storage.resolvePath(key)?.toFile()?.length()?.takeIf { it > 0 }
                                ?: req.size
                            val (orphans, snapshot) = VersionService.snapshotAndReplace(
                                clash[FilesTable.id],
                                clash[FilesTable.sha256], clash[FilesTable.storageKey], clash[FilesTable.size], clash[FilesTable.mimeType], clash[FilesTable.takenAt],
                                sha, key, storedSize, req.mimeType ?: clash[FilesTable.mimeType], req.takenAt ?: clash[FilesTable.takenAt],
                                hasThumb, System.currentTimeMillis(),
                            )
                            val updated = FilesTable.selectAll().where { FilesTable.id eq clash[FilesTable.id] }.single().toFileDto()
                            Triple(updated, orphans, snapshot as FileVersionDto?)
                            }
                        }
                        clash != null -> null
                        FileService.nameConflict(userId, parent, req.name) -> null
                        else -> {
                            // Never trust a client-declared size for dedup: reuse the size
                            // already recorded for this content, or the stored blob length.
                            val storedSize = FilesTable.selectAll().where { FilesTable.storageKey eq key }
                                .firstOrNull()?.get(FilesTable.size)
                                ?: storage.resolvePath(key)?.toFile()?.length()?.takeIf { it > 0 }
                            val id = UUID.randomUUID()
                            mapNameConflict {
                                FilesTable.insert {
                                    it[FilesTable.id] = id; it[user] = userId; it[folder] = parent
                                    it[FilesTable.name] = req.name; it[FilesTable.size] = storedSize ?: req.size
                                    it[mimeType] = req.mimeType; it[FilesTable.sha256] = sha; it[storageKey] = key
                                    it[hasThumbnail] = hasThumb
                                    it[takenAt] = req.takenAt
                                }
                            }
                            Triple(FilesTable.selectAll().where { FilesTable.id eq id }.single().toFileDto(), emptyList<String>(), null as FileVersionDto?)
                        }
                    }
                }
                if (instant != null) {
                    val (created, orphans, _) = instant
                    if (orphans.isNotEmpty()) deleteStoredBlobs(storage, orphans)
                    return UploadInitResponse("", chunkSize, emptyList(), instantUpload = true, file = created)
                }
            }
        }

        // Resume match: open session with the same user, folder, name and size.
        // Expired rows are skipped: the cleanup job runs only every 6h, and handing
        // back a dead session id would lock the client in 409 loops until then.
        val nowMs = System.currentTimeMillis()
        val existing = transaction(DatabaseFactory.db) {
            (if (parent == null)
                UploadSessionsTable.selectAll().where { (UploadSessionsTable.user eq userId) and UploadSessionsTable.folder.isNull() and (UploadSessionsTable.name eq req.name) and (UploadSessionsTable.size eq req.size) and (UploadSessionsTable.status eq "open") and (UploadSessionsTable.expiresAt greater nowMs) }
            else
                UploadSessionsTable.selectAll().where { (UploadSessionsTable.user eq userId) and (UploadSessionsTable.folder eq parent) and (UploadSessionsTable.name eq req.name) and (UploadSessionsTable.size eq req.size) and (UploadSessionsTable.status eq "open") and (UploadSessionsTable.expiresAt greater nowMs) }
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
                    it[overwrite] = req.overwrite
                }
            }
        }
        // A resumed session was created by an earlier attempt that may not have
        // opted into overwrite yet: honor the newest user choice.
        if (req.overwrite && existing != null && !existing[UploadSessionsTable.overwrite]) {
            transaction(DatabaseFactory.db) {
                UploadSessionsTable.update({ UploadSessionsTable.id eq sessionId }) { it[overwrite] = true }
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
        // A chunk row whose bytes are gone (tmp dir pruned by a restart or by the
        // cleanup loop) must not be reported as received: the client would skip it
        // and every complete would answer CHUNK_MISSING until the session expires.
        val stagingDir = storage.tmpDir.resolve(sessionId.toString()).toFile()
        val present = received.filter { File(stagingDir, "$it.part").exists() }
        return UploadInitResponse(sessionId.toString(), chunkSize, present.sorted())
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
        // Written to a private staging name and only renamed onto the final slot
        // after it validates: the blob path does the same with put-*.tmp. Two
        // devices resuming the same index would otherwise truncate each other's
        // half-written file and produce a corrupt merge later on.
        val staging = Files.createTempFile(dir.toPath(), "$index-", ".part.tmp")
        val part = File(dir, "$index.part")
        try {
            withContext(Dispatchers.IO) {
                FileOutputStream(staging.toFile()).use { out ->
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
            if (written != expectedSize) throw ApiException.badRequest("分块大小不符", ErrorCodes.CHUNK_INVALID)
            if (chunkSha != null && !chunkSha.equals(digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }, ignoreCase = true)) {
                throw ApiException.badRequest("分块校验不符", ErrorCodes.CHUNK_INVALID)
            }
            withContext(Dispatchers.IO) { publishChunk(staging, part) }
        } finally {
            // Only the staging file belongs to this request: a rejected chunk must
            // never destroy a part an earlier attempt already published.
            runCatching { Files.deleteIfExists(staging) }
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

    private fun publishChunk(staging: java.nio.file.Path, target: File) {
        try {
            Files.move(staging, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // Filesystems without atomic rename (some network mounts) still get
            // replace-existing, which is what the single-writer case needs.
            Files.move(staging, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
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

        // One merge file per attempt: two concurrent completes of this session (or
        // of two sessions racing for the same name) must not share a buffer.
        val mergeFile = Files.createTempFile(storage.tmpDir, "merge-$sessionId-", ".bin").toFile()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(mergeFile).use { out ->
                val buf = ByteArray(1 shl 16)
                for (i in 0 until expected) {
                    val part = storage.tmpDir.resolve(sessionId.toString()).resolve("$i.part").toFile()
                    // The chunk rows say the bytes arrived; if the staging dir is
                    // gone (pruned, or a diverged restore) that is a 400 CHUNK_MISSING,
                    // not a FileNotFoundException surfacing as a 500.
                    if (!part.exists()) throw ApiException.badRequest("缺少分块", ErrorCodes.CHUNK_MISSING)
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
            if (!storage.exists(key)) {
                FileInputStream(mergeFile).use { fis -> storage.put(key, fis.toByteReadChannel()) }
            }
            val hasThumb = storage.exists(thumbKey(sha))
            val overwrite = row[UploadSessionsTable.overwrite]
            val (fileDto, orphanKeys, replacedVersion) = transaction(DatabaseFactory.db) {
                val clash = if (overwrite) FileService.findLiveFile(userId, parent, name) else null
                if (clash != null) {
                    // Overwrite onto a live file: the previous content becomes a
                    // version snapshot, the row keeps its id (share links and
                    // flags survive the replacement).
                    val fileId = clash[FilesTable.id]
                    val (orphans, snapshot) = if (clash[FilesTable.sha256] == sha) {
                        FilesTable.update({ FilesTable.id eq fileId }) { it[updatedAt] = System.currentTimeMillis() }
                        emptyList<String>() to null
                    } else {
                        VersionService.snapshotAndReplace(
                            fileId,
                            clash[FilesTable.sha256], clash[FilesTable.storageKey], clash[FilesTable.size], clash[FilesTable.mimeType], clash[FilesTable.takenAt],
                            sha, key, total, mime, sessionTakenAt ?: clash[FilesTable.takenAt],
                            hasThumb, System.currentTimeMillis(),
                        )
                    }
                    UploadSessionsTable.update({ UploadSessionsTable.id eq sessionId }) { it[status] = "completed" }
                    Triple(FilesTable.selectAll().where { FilesTable.id eq fileId }.single().toFileDto(), orphans, snapshot)
                } else {
                    if (FileService.nameConflict(userId, parent, name)) throw ApiException.conflict(ErrorCodes.NAME_CONFLICT, "同级已存在同名文件夹或文件")
                    val id = UUID.randomUUID()
                    mapNameConflict {
                        FilesTable.insert {
                            it[FilesTable.id] = id; it[user] = userId; it[folder] = parent
                            it[FilesTable.name] = name; it[FilesTable.size] = total
                            it[mimeType] = mime; it[FilesTable.sha256] = sha; it[storageKey] = key
                            it[hasThumbnail] = hasThumb
                            it[takenAt] = sessionTakenAt
                        }
                    }
                    UploadSessionsTable.update({ UploadSessionsTable.id eq sessionId }) { it[status] = "completed" }
                    Triple(FilesTable.selectAll().where { FilesTable.id eq id }.single().toFileDto(), emptyList<String>(), null as FileVersionDto?)
                }
            }
            cleanupSessionDir(storage, sessionId)
            if (orphanKeys.isNotEmpty()) deleteStoredBlobs(storage, orphanKeys)
            // When the client sent no cover (instant upload, the web client,
            // third-party API callers), generate one in the background now: by
            // the time the user opens a list, the tile shows a real cover
            // instead of waiting for a first GET to trigger lazy generation.
            if (!hasThumb) {
                val meta = FileMeta(
                    id = UUID.fromString(fileDto.id), userId = userId, folderId = parent, name = name,
                    size = total, mimeType = mime, sha256 = sha, storageKey = key,
                    hasThumbnail = false,
                )
                bgScope.launch { runCatching { ThumbnailService.ensureThumbnail(storage, meta) } }
            }
            UploadCompleteResponse(fileDto, replacedVersion)
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
        // Any expired session goes - open (abandoned uploads), but also aborted and
        // completed rows, whose staging dirs and chunk rows nothing else ever prunes.
        val expired = transaction(DatabaseFactory.db) {
            UploadSessionsTable.selectAll()
                .where { UploadSessionsTable.expiresAt less now }
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
        // Expired refresh tokens (revoked or not) are dead weight. One statement,
        // not one round trip per row: a server that was offline for a month can
        // have thousands of them. The condition is built outside the deleteWhere
        // lambda because that scope is the table, not the full operator set.
        val stale = SqlExpressionBuilder.run { RefreshTokensTable.expiresAt less now }
        transaction(DatabaseFactory.db) {
            RefreshTokensTable.deleteWhere { stale }
        }
    }

    private fun cleanupSessionDir(storage: StorageProvider, sessionId: UUID) {
        storage.tmpDir.resolve(sessionId.toString()).toFile().deleteRecursively()
    }
}
