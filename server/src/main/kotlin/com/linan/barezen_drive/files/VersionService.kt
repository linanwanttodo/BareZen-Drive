package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.FileVersionDto
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FileVersionsTable
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.storage.StorageProvider
import com.linan.barezen_drive.storage.thumbKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * History of superseded file contents. A row here is a full content snapshot
 * (blob key + metadata), kept alive by the same reference counting as files:
 * a blob is garbage only when no file row AND no version row reference it.
 *
 * Snapshots are taken by the overwrite upload path (see UploadService) and by
 * restore, which moves the live content into history before adopting the
 * selected revision. Old revisions beyond [MAX_VERSIONS] are trimmed silently;
 * the trimmed keys' last-reference check happens inside the same transaction,
 * so the caller only has to purge the returned keys.
 */
object VersionService {

    /** Revisions kept per file before the oldest ones are dropped. */
    const val MAX_VERSIONS = 20

    /**
     * Insert a version row for the content currently on [fileId] and rewrite
     * the file row to the new content. Must run inside the caller's
     * transaction; returns the orphaned blob keys (from trimming) plus the
     * snapshot that now represents the previous head. The caller purges the
     * keys after commit.
     */
    fun snapshotAndReplace(
        fileId: UUID,
        oldSha: String, oldKey: String, oldSize: Long, oldMime: String?, oldTakenAt: Long?,
        newSha: String, newKey: String, newSize: Long, newMime: String?, newTakenAt: Long?,
        newHasThumb: Boolean, now: Long,
    ): Pair<List<String>, FileVersionDto> {
        // Lock the file row first: two concurrent overwrites of the same file
        // would otherwise both read the same max revision and the loser would hit
        // the (file, revision) unique index. The winner's transaction then simply
        // continues on the next revision number.
        FilesTable.selectAll().where { FilesTable.id eq fileId }.forUpdate().single()
        val nextRevision = (FileVersionsTable.selectAll().where { FileVersionsTable.file eq fileId }
            .maxOfOrNull { it[FileVersionsTable.revision] } ?: 0L) + 1L
        val versionId = UUID.randomUUID()
        FileVersionsTable.insert {
            it[id] = versionId
            it[file] = fileId
            it[revision] = nextRevision
            it[sha256] = oldSha
            it[storageKey] = oldKey
            it[size] = oldSize
            it[mimeType] = oldMime
            it[takenAt] = oldTakenAt
            it[createdAt] = now
        }
        val snapshot = FileVersionsTable.selectAll().where { FileVersionsTable.id eq versionId }.single().toFileVersionDto()
        FilesTable.update({ FilesTable.id eq fileId }) {
            it[sha256] = newSha
            it[storageKey] = newKey
            it[size] = newSize
            it[mimeType] = newMime
            it[takenAt] = newTakenAt
            it[hasThumbnail] = newHasThumb
            it[updatedAt] = now
        }
        return trim(fileId) to snapshot
    }

    /** Drops revisions older than the retention cap; returns orphaned keys. */
    private fun trim(fileId: UUID, now: Long = System.currentTimeMillis()): List<String> {
        val rows = FileVersionsTable.selectAll().where { FileVersionsTable.file eq fileId }
            .orderBy(FileVersionsTable.revision, SortOrder.DESC)
            .map { it.idValue() to it[FileVersionsTable.storageKey] }
        if (rows.size <= MAX_VERSIONS) return emptyList()
        val doomed = rows.drop(MAX_VERSIONS)
        val op = SqlExpressionBuilder.run { FileVersionsTable.id inList doomed.map { it.first } }
        FileVersionsTable.deleteWhere { op }
        // The live row was already rewritten by the caller, so counting after
        // the delete is exact for both directions (a trimmed key may still be
        // the live content of this very file when the revision cycle closed).
        return FileService.orphanBlobKeys(doomed.map { it.second })
    }

    private fun ResultRow.idValue(): UUID = this[FileVersionsTable.id]

    fun list(userId: UUID, fileId: UUID): List<FileVersionDto> = transaction(DatabaseFactory.db) {
        FileService.fileRowOf(userId, fileId)
        FileVersionsTable.selectAll().where { FileVersionsTable.file eq fileId }
            .orderBy(FileVersionsTable.revision, SortOrder.DESC)
            .map { it.toFileVersionDto() }
    }

    /**
     * Swap the live content with revision [versionId]: the current content
     * becomes a new snapshot, the chosen one becomes live, and its old row is
     * removed (its bytes now belong to the file row again).
     */
    suspend fun restore(userId: UUID, fileId: UUID, versionId: UUID, storage: StorageProvider): FileDto {
        // Cover existence must be probed outside the transaction (storage IO).
        val vSha = transaction(DatabaseFactory.db) {
            FileService.fileRowOf(userId, fileId)
            FileVersionsTable.selectAll().where { (FileVersionsTable.id eq versionId) and (FileVersionsTable.file eq fileId) }
                .singleOrNull() ?: throw ApiException.notFound("版本不存在")
        }[FileVersionsTable.sha256]
        val hasThumb = withContext(Dispatchers.IO) { storage.exists(thumbKey(vSha)) }
        val (orphans, dto) = transaction(DatabaseFactory.db) {
            val file = FileService.fileRowOf(userId, fileId)
            val v = FileVersionsTable.selectAll().where { (FileVersionsTable.id eq versionId) and (FileVersionsTable.file eq fileId) }
                .singleOrNull() ?: throw ApiException.notFound("版本不存在")
            val now = System.currentTimeMillis()
            val (keys, _) = snapshotAndReplace(
                fileId,
                file[FilesTable.sha256], file[FilesTable.storageKey], file[FilesTable.size], file[FilesTable.mimeType], file[FilesTable.takenAt],
                v[FileVersionsTable.sha256], v[FileVersionsTable.storageKey], v[FileVersionsTable.size], v[FileVersionsTable.mimeType], v[FileVersionsTable.takenAt],
                hasThumb, now,
            )
            // The restored revision is now live content: drop its version row
            // without touching the just-created snapshot of the previous head.
            // snapshotAndReplace never reuses ids, so this removes exactly one row.
            FileVersionsTable.deleteWhere { FileVersionsTable.id eq versionId }
            val merged = (keys + FileService.orphanBlobKeys(listOf(v[FileVersionsTable.storageKey]))).distinct()
            val result = FilesTable.selectAll().where { FilesTable.id eq fileId }.single().toFileDto()
            merged to result
        }
        withContext(Dispatchers.IO) { deleteStoredBlobs(storage, orphans) }
        return dto
    }

    /** Removes a single revision; purges its blob when it became unreferenced. */
    suspend fun deleteVersion(userId: UUID, fileId: UUID, versionId: UUID, storage: StorageProvider) {
        val key = transaction(DatabaseFactory.db) {
            FileService.fileRowOf(userId, fileId)
            val v = FileVersionsTable.selectAll().where { (FileVersionsTable.id eq versionId) and (FileVersionsTable.file eq fileId) }
                .singleOrNull() ?: throw ApiException.notFound("版本不存在")
            FileVersionsTable.deleteWhere { FileVersionsTable.id eq versionId }
            v[FileVersionsTable.storageKey]
        }
        val orphans = transaction(DatabaseFactory.db) { FileService.orphanBlobKeys(listOf(key)) }
        withContext(Dispatchers.IO) { deleteStoredBlobs(storage, orphans) }
    }

    internal fun ResultRow.toFileVersionDto(): FileVersionDto = FileVersionDto(
        this[FileVersionsTable.id].toString(),
        this[FileVersionsTable.file].toString(),
        this[FileVersionsTable.revision],
        this[FileVersionsTable.size],
        this[FileVersionsTable.sha256],
        this[FileVersionsTable.mimeType],
        this[FileVersionsTable.takenAt]?.let { Instant.ofEpochMilli(it).toString() },
        Instant.ofEpochMilli(this[FileVersionsTable.createdAt]).toString(),
    )
}
