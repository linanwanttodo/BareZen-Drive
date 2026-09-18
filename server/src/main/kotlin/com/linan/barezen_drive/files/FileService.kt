package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.mapNameConflict
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.core.dto.*
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FileVersionsTable
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.ShareLinksTable
import com.linan.barezen_drive.db.UploadChunksTable
import com.linan.barezen_drive.db.UploadSessionsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

data class FileMeta(val id: UUID, val userId: UUID, val folderId: UUID?, val name: String, val size: Long, val mimeType: String?, val sha256: String, val storageKey: String, val hasThumbnail: Boolean)

object FileService {
    private const val MILLIS_PER_DAY = 24L * 3600 * 1000

    /** How long a trashed file waits before the cleanup loop purges it. */
    const val TRASH_RETENTION_DAYS = 30L

    private fun nameOk(name: String) = name.isNotBlank() && name.length <= 255 && !name.contains('/')

    fun contents(userId: UUID, folderId: String): ContentsResponse = transaction(DatabaseFactory.db) {
        val parent: UUID? = if (folderId == "root") null else {
            val id = folderId.toUuidOrBadRequest()
            FoldersTable.selectAll().where { (FoldersTable.id eq id) and (FoldersTable.user eq userId) }.singleOrNull()
                ?: throw ApiException.notFound("文件夹不存在")
            id
        }
        val folders = (if (parent == null) {
            FoldersTable.selectAll().where { (FoldersTable.user eq userId) and FoldersTable.parent.isNull() }
        } else {
            FoldersTable.selectAll().where { (FoldersTable.user eq userId) and (FoldersTable.parent eq parent) }
        }).map { it.toFolderDto() }
        val files = (if (parent == null) {
            FilesTable.selectAll().where { (FilesTable.user eq userId) and FilesTable.folder.isNull() and (FilesTable.deletedAt eq 0L) }
        } else {
            FilesTable.selectAll().where { (FilesTable.user eq userId) and (FilesTable.folder eq parent) and (FilesTable.deletedAt eq 0L) }
        }).map { it.toFileDto() }
        val cur = parent?.let { pid -> FoldersTable.selectAll().where { FoldersTable.id eq pid }.single().toFolderDto() }
        ContentsResponse(cur, folders.sortedBy { it.name.lowercase() }, files.sortedBy { it.name.lowercase() })
    }

    /** Folders and files share one sibling namespace: a clash on either side is a conflict.
     *  Trashed rows are not part of the namespace (see the live-only filters inside). */
    internal fun nameConflict(userId: UUID, parent: UUID?, name: String, excludeFolder: UUID? = null, excludeFile: UUID? = null): Boolean {
        val folders = if (parent == null) {
            FoldersTable.selectAll().where { (FoldersTable.user eq userId) and FoldersTable.parent.isNull() and (FoldersTable.name eq name) }
        } else {
            FoldersTable.selectAll().where { (FoldersTable.user eq userId) and (FoldersTable.parent eq parent) and (FoldersTable.name eq name) }
        }
        val folderHit = if (excludeFolder != null) folders.andWhere { FoldersTable.id neq excludeFolder }.any() else folders.any()
        // A trashed row does not hold the name: the same file can be uploaded
        // again while the old copy waits in the trash.
        val files = if (parent == null) {
            FilesTable.selectAll().where { (FilesTable.user eq userId) and FilesTable.folder.isNull() and (FilesTable.name eq name) and (FilesTable.deletedAt eq 0L) }
        } else {
            FilesTable.selectAll().where { (FilesTable.user eq userId) and (FilesTable.folder eq parent) and (FilesTable.name eq name) and (FilesTable.deletedAt eq 0L) }
        }
        val fileHit = if (excludeFile != null) files.andWhere { FilesTable.id neq excludeFile }.any() else files.any()
        return folderHit || fileHit
    }

    /** Live (non-trashed) file row holding [name] under [parent], if any. The
     *  overwrite upload path replaces exactly this row; a folder of the same
     *  name is a different animal and never a replace target. */
    internal fun findLiveFile(userId: UUID, parent: UUID?, name: String): ResultRow? =
        (if (parent == null) {
            FilesTable.selectAll().where { (FilesTable.user eq userId) and FilesTable.folder.isNull() and (FilesTable.name eq name) and (FilesTable.deletedAt eq 0L) }
        } else {
            FilesTable.selectAll().where { (FilesTable.user eq userId) and (FilesTable.folder eq parent) and (FilesTable.name eq name) and (FilesTable.deletedAt eq 0L) }
        }).firstOrNull()

    internal fun hasFolderNamed(userId: UUID, parent: UUID?, name: String): Boolean =
        if (parent == null) {
            FoldersTable.selectAll().where { (FoldersTable.user eq userId) and FoldersTable.parent.isNull() and (FoldersTable.name eq name) }.any()
        } else {
            FoldersTable.selectAll().where { (FoldersTable.user eq userId) and (FoldersTable.parent eq parent) and (FoldersTable.name eq name) }.any()
        }

    fun createFolder(userId: UUID, parentId: String?, name: String): FolderDto = transaction(DatabaseFactory.db) {
        if (!nameOk(name)) throw ApiException.badRequest("名称非法")
        val parent: UUID? = parentId?.let {
            val pid = it.toUuidOrBadRequest()
            FoldersTable.selectAll().where { (FoldersTable.id eq pid) and (FoldersTable.user eq userId) }.singleOrNull()
                ?: throw ApiException.notFound("目标文件夹不存在")
            pid
        }
        if (nameConflict(userId, parent, name)) throw ApiException.conflict(ErrorCodes.NAME_CONFLICT, "同级已存在同名文件夹或文件")
        val id = UUID.randomUUID()
        val now = System.currentTimeMillis()
        mapNameConflict {
            FoldersTable.insert {
                it[FoldersTable.id] = id; it[user] = userId; it[FoldersTable.parent] = parent; it[FoldersTable.name] = name
            }
        }
        val iso = Instant.ofEpochMilli(now).toString()
        FolderDto(id.toString(), name, parent?.toString(), iso, iso)
    }

    fun renameFolder(userId: UUID, id: UUID, newName: String): FolderDto = transaction(DatabaseFactory.db) {
        val row = FoldersTable.selectAll().where { (FoldersTable.id eq id) and (FoldersTable.user eq userId) }.singleOrNull()
            ?: throw ApiException.notFound("文件夹不存在")
        if (!nameOk(newName)) throw ApiException.badRequest("名称非法")
        if (nameConflict(userId, row[FoldersTable.parent], newName, excludeFolder = id)) {
            throw ApiException.conflict(ErrorCodes.NAME_CONFLICT, "同级已存在同名文件夹或文件")
        }
        mapNameConflict {
            FoldersTable.update({ FoldersTable.id eq id }) {
                it[name] = newName; it[updatedAt] = System.currentTimeMillis()
            }
        }
        row.toFolderDto().copy(name = newName)
    }

    /** Folder deletion result: blob keys whose refcount drops to zero, and upload
     * sessions (removed rows) that targeted the deleted subtree. */
    data class FolderDeletion(val blobKeys: List<String>, val removedSessionIds: List<UUID>)

    /** Recursively delete the folder subtree; returns storage keys whose blob refcount drops to zero. */
    fun deleteFolder(userId: UUID, id: UUID): FolderDeletion = transaction(DatabaseFactory.db) {
        FoldersTable.selectAll().where { (FoldersTable.id eq id) and (FoldersTable.user eq userId) }.singleOrNull()
            ?: throw ApiException.notFound("文件夹不存在")
        // The whole subtree in one query (FileTree); walking it level by level
        // would cost one round trip per folder on the way down.
        val all = FileTree.subtreeWithRoot(userId, id)
        // ALL sessions (any status) targeting the subtree reference folders.id by FK;
        // their rows must be removed here or folder row deletion fails. One query
        // for the ids, then two batched deletes - chunks first, sessions second.
        val doomedSessionIds = UploadSessionsTable.selectAll()
            .where { UploadSessionsTable.folder inList all }
            .map { it[UploadSessionsTable.id] }
        if (doomedSessionIds.isNotEmpty()) {
            val chunksOp = SqlExpressionBuilder.run { UploadChunksTable.session inList doomedSessionIds }
            UploadChunksTable.deleteWhere { chunksOp }
            val sessionsOp = SqlExpressionBuilder.run { UploadSessionsTable.id inList doomedSessionIds }
            UploadSessionsTable.deleteWhere { sessionsOp }
        }
        // One query yields both the live blob keys and the file ids; the version
        // snapshots of those files must leave before the file rows (FK) and count
        // towards the same refcount sweep as the live keys.
        val doomedFiles = FilesTable.selectAll()
            .where { FilesTable.folder inList all }
            .map { it[FilesTable.id] to it[FilesTable.storageKey] }
        val keys = doomedFiles.mapTo(mutableListOf()) { it.second }
        val doomedFileIds = doomedFiles.map { it.first }
        if (doomedFileIds.isNotEmpty()) {
            val versionOp = SqlExpressionBuilder.run { FileVersionsTable.file inList doomedFileIds }
            FileVersionsTable.selectAll().where { versionOp }.forEach { keys.add(it[FileVersionsTable.storageKey]) }
            FileVersionsTable.deleteWhere { versionOp }
            val filesOp = SqlExpressionBuilder.run { FilesTable.id inList doomedFileIds }
            FilesTable.deleteWhere { filesOp }
        }
        // Delete children before parents so the self-referencing FK (parent_id) is satisfied.
        for (fid in all.asReversed()) FoldersTable.deleteWhere { FoldersTable.id eq fid }
        FolderDeletion(orphanBlobKeys(keys), doomedSessionIds)
    }

    fun getFileMeta(userId: UUID, id: UUID): FileMeta = transaction(DatabaseFactory.db) {
        val r = FilesTable.selectAll().where { (FilesTable.id eq id) and (FilesTable.user eq userId) }.singleOrNull()
            ?: throw ApiException.notFound("文件不存在")
        r.toFileMeta()
    }

    /** Owner-agnostic metadata lookup, only for signature-authenticated access.
     *  Trashed rows are excluded: moving a file to the trash revokes its share
     *  links, and its signed /link URLs must die the same way. */
    fun getFileMetaUnscoped(id: UUID): FileMeta = transaction(DatabaseFactory.db) {
        FilesTable.selectAll().where { (FilesTable.id eq id) and (FilesTable.deletedAt eq 0L) }.singleOrNull()?.toFileMeta()
            ?: throw ApiException.notFound("文件不存在")
    }

    private fun ResultRow.toFileMeta() = FileMeta(
        this[FilesTable.id], this[FilesTable.user], this[FilesTable.folder], this[FilesTable.name],
        this[FilesTable.size], this[FilesTable.mimeType], this[FilesTable.sha256], this[FilesTable.storageKey],
        this[FilesTable.hasThumbnail],
    )

    fun updateFile(userId: UUID, id: UUID, newName: String?, newFolderId: String?): FileDto = transaction(DatabaseFactory.db) {
        val cur = getFileMeta(userId, id)
        if (newName != null) {
            if (!nameOk(newName)) throw ApiException.badRequest("名称非法")
        }
        val target: UUID? = when (newFolderId) {
            null -> cur.folderId
            "root" -> null
            else -> {
                val tid = newFolderId.toUuidOrBadRequest()
                FoldersTable.selectAll().where { (FoldersTable.id eq tid) and (FoldersTable.user eq userId) }.singleOrNull()
                    ?: throw ApiException.notFound("目标文件夹不存在")
                tid
            }
        }
        val finalName = newName ?: cur.name
        // Files have no subtree, so no into-descendant check is needed; only the sibling name clash.
        if (nameConflict(userId, target, finalName, excludeFile = id)) {
            throw ApiException.conflict(ErrorCodes.NAME_CONFLICT, "目标位置已存在同名文件夹或文件")
        }
        mapNameConflict {
            FilesTable.update({ FilesTable.id eq id }) {
                if (newName != null) it[name] = newName
                it[folder] = target
                it[updatedAt] = System.currentTimeMillis()
            }
        }
        FilesTable.selectAll().where { FilesTable.id eq id }.singleOrNull()?.toFileDto()
                ?: throw ApiException.notFound("文件不存在")
    }

    /**
     * Soft delete: the row keeps existing (so its blob still has a reference and
     * the id stays restorable) and only gains a trash timestamp. Physical
     * removal happens through the trash endpoints or the retention sweep.
     */
    fun trashFile(userId: UUID, id: UUID): FileDto = transaction(DatabaseFactory.db) {
        fileRowOf(userId, id)
        val now = System.currentTimeMillis()
        FilesTable.update({ FilesTable.id eq id }) {
            it[deletedAt] = now
            it[updatedAt] = now
        }
        // A trashed file must stop serving its public links immediately. They are
        // revoked rather than deleted (view/download counters survive), and
        // restoring the file does not silently re-publish them: re-sharing stays
        // an explicit act.
        val activeLinks = SqlExpressionBuilder.run { (ShareLinksTable.file eq id) and ShareLinksTable.revokedAt.isNull() }
        ShareLinksTable.update({ activeLinks }) { it[revokedAt] = now }
        FilesTable.selectAll().where { FilesTable.id eq id }.singleOrNull()?.toFileDto()
                ?: throw ApiException.notFound("文件不存在")
    }

    /** Everything currently in the trash, most recently trashed first. */
    fun listTrash(userId: UUID): List<FileDto> = transaction(DatabaseFactory.db) {
        FilesTable.selectAll()
            .where { (FilesTable.user eq userId) and (FilesTable.deletedAt greater 0L) }
            .orderBy(FilesTable.deletedAt to SortOrder.DESC)
            .map { it.toFileDto() }
    }

    /** Puts a trashed file back on the shelf. A live sibling already holding the
     *  name is a 409, so the client can rename and retry instead of losing rows. */
    fun restoreFile(userId: UUID, id: UUID): FileDto = transaction(DatabaseFactory.db) {
        val row = fileRowOf(userId, id)
        if (row[FilesTable.deletedAt] == 0L) return@transaction row.toFileDto()
        if (nameConflict(userId, row[FilesTable.folder], row[FilesTable.name], excludeFile = id)) {
            throw ApiException.conflict(ErrorCodes.NAME_CONFLICT, "目标位置已存在同名文件夹或文件")
        }
        mapNameConflict {
            FilesTable.update({ FilesTable.id eq id }) {
                it[deletedAt] = 0L
                it[updatedAt] = System.currentTimeMillis()
            }
        }
        FilesTable.selectAll().where { FilesTable.id eq id }.singleOrNull()?.toFileDto()
                ?: throw ApiException.notFound("文件不存在")
    }

    /** Permanent removal of a trashed file; returns blob keys that lost their last reference. */
    fun deleteForever(userId: UUID, id: UUID): List<String> = transaction(DatabaseFactory.db) {
        val row = fileRowOf(userId, id)
        // A live row is refused on purpose: this endpoint must stay the only way
        // to skip the trash, so a buggy client cannot bypass it by accident.
        if (row[FilesTable.deletedAt] == 0L) throw ApiException.badRequest("文件不在回收站中")
        hardDelete(listOf(id to row[FilesTable.storageKey]))
    }

    /** Empties the whole trash; returns blob keys that lost their last reference. */
    fun emptyTrash(userId: UUID): List<String> = transaction(DatabaseFactory.db) {
        hardDelete(
            FilesTable.selectAll()
                .where { (FilesTable.user eq userId) and (FilesTable.deletedAt greater 0L) }
                .map { it[FilesTable.id] to it[FilesTable.storageKey] }
        )
    }

    /**
     * Purges trash rows older than [retentionDays] and returns the blob keys
     * whose last reference went away, for the caller to delete physically.
     */
    fun purgeExpiredTrash(retentionDays: Long = TRASH_RETENTION_DAYS, now: Long = System.currentTimeMillis()): List<String> {
        val cutoff = now - retentionDays * MILLIS_PER_DAY
        return transaction(DatabaseFactory.db) {
            hardDelete(
                FilesTable.selectAll()
                    .where { (FilesTable.deletedAt greater 0L) and (FilesTable.deletedAt lessEq cutoff) }
                    .map { it[FilesTable.id] to it[FilesTable.storageKey] }
            )
        }
    }

    fun setFavorite(userId: UUID, id: UUID, favorite: Boolean): FileDto = transaction(DatabaseFactory.db) {
        fileRowOf(userId, id)
        FilesTable.update({ FilesTable.id eq id }) { it[isFavorite] = favorite }
        FilesTable.selectAll().where { FilesTable.id eq id }.singleOrNull()?.toFileDto()
                ?: throw ApiException.notFound("文件不存在")
    }

    /** Archive is a flag, not a move: the file keeps its folder and name. */
    fun setArchived(userId: UUID, id: UUID, archived: Boolean): FileDto = transaction(DatabaseFactory.db) {
        fileRowOf(userId, id)
        val now = System.currentTimeMillis()
        FilesTable.update({ FilesTable.id eq id }) {
            it[archivedAt] = if (archived) now else 0L
            it[updatedAt] = now
        }
        FilesTable.selectAll().where { FilesTable.id eq id }.singleOrNull()?.toFileDto()
                ?: throw ApiException.notFound("文件不存在")
    }

    /** Row of a file owned by [userId] regardless of trash state - the trash
     *  routes must reach rows every live query filters out. */
    internal fun fileRowOf(userId: UUID, id: UUID): ResultRow =
        FilesTable.selectAll().where { (FilesTable.id eq id) and (FilesTable.user eq userId) }.singleOrNull()
            ?: throw ApiException.notFound("文件不存在")

    /**
     * Keys from [candidateKeys] that no row references any more - neither a
     * file (live or trashed) nor a version snapshot. Runs inside the caller's
     * transaction so the counting sees the very write that dropped the last
     * reference.
     */
    internal fun orphanBlobKeys(candidateKeys: List<String>): List<String> {
        if (candidateKeys.isEmpty()) return emptyList()
        return candidateKeys.distinct().filter { key ->
            FilesTable.selectAll().where { FilesTable.storageKey eq key }.count() == 0L &&
                FileVersionsTable.selectAll().where { FileVersionsTable.storageKey eq key }.count() == 0L
        }
    }

    /** One batched DELETE for [rows], then the refcount sweep that decides which
     *  blobs are now unreferenced. An empty list costs no query at all. */
    private fun hardDelete(rows: List<Pair<UUID, String>>): List<String> {
        if (rows.isEmpty()) return emptyList()
        // Built outside the deleteWhere lambda: that scope is the table, which
        // does not expose the full operator set (see the note in UploadService).
        val fileIds = rows.map { it.first }
        val versionOp = SqlExpressionBuilder.run { FileVersionsTable.file inList fileIds }
        val versionKeys = FileVersionsTable.selectAll().where { versionOp }.map { it[FileVersionsTable.storageKey] }
        FileVersionsTable.deleteWhere { versionOp }
        val op = SqlExpressionBuilder.run { FilesTable.id inList fileIds }
        FilesTable.deleteWhere { op }
        return orphanBlobKeys(rows.map { it.second } + versionKeys)
    }
}

internal fun ResultRow.toFolderDto(): FolderDto = FolderDto(
    this[FoldersTable.id].toString(), this[FoldersTable.name],
    this[FoldersTable.parent]?.toString(), Instant.ofEpochMilli(this[FoldersTable.createdAt]).toString(), Instant.ofEpochMilli(this[FoldersTable.updatedAt]).toString(),
)

internal fun ResultRow.toFileDto(): FileDto = FileDto(
    this[FilesTable.id].toString(), this[FilesTable.name], this[FilesTable.folder]?.toString(),
    this[FilesTable.size], this[FilesTable.mimeType], this[FilesTable.sha256],
    Instant.ofEpochMilli(this[FilesTable.createdAt]).toString(), Instant.ofEpochMilli(this[FilesTable.updatedAt]).toString(),
    this[FilesTable.hasThumbnail],
    this[FilesTable.takenAt]?.let { Instant.ofEpochMilli(it).toString() },
    this[FilesTable.isFavorite],
    // 0 is the column's "not set" sentinel; the DTO speaks null.
    this[FilesTable.archivedAt].takeIf { it != 0L }?.let { Instant.ofEpochMilli(it).toString() },
    this[FilesTable.deletedAt].takeIf { it != 0L }?.let { Instant.ofEpochMilli(it).toString() },
)
