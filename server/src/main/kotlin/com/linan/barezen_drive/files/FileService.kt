package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.core.dto.*
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.UploadChunksTable
import com.linan.barezen_drive.db.UploadSessionsTable
import io.ktor.http.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

data class FileMeta(val id: UUID, val userId: UUID, val folderId: UUID?, val name: String, val size: Long, val mimeType: String?, val sha256: String, val storageKey: String, val hasThumbnail: Boolean)

object FileService {
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
            FilesTable.selectAll().where { (FilesTable.user eq userId) and FilesTable.folder.isNull() }
        } else {
            FilesTable.selectAll().where { (FilesTable.user eq userId) and (FilesTable.folder eq parent) }
        }).map { it.toFileDto() }
        val cur = parent?.let { pid -> FoldersTable.selectAll().where { FoldersTable.id eq pid }.single().toFolderDto() }
        ContentsResponse(cur, folders.sortedBy { it.name.lowercase() }, files.sortedBy { it.name.lowercase() })
    }

    /** Folders and files share one sibling namespace: a clash on either side is a conflict. */
    private fun nameConflict(userId: UUID, parent: UUID?, name: String, excludeFolder: UUID? = null, excludeFile: UUID? = null): Boolean {
        val folders = if (parent == null) {
            FoldersTable.selectAll().where { (FoldersTable.user eq userId) and FoldersTable.parent.isNull() and (FoldersTable.name eq name) }
        } else {
            FoldersTable.selectAll().where { (FoldersTable.user eq userId) and (FoldersTable.parent eq parent) and (FoldersTable.name eq name) }
        }
        val folderHit = if (excludeFolder != null) folders.andWhere { FoldersTable.id neq excludeFolder }.any() else folders.any()
        val files = if (parent == null) {
            FilesTable.selectAll().where { (FilesTable.user eq userId) and FilesTable.folder.isNull() and (FilesTable.name eq name) }
        } else {
            FilesTable.selectAll().where { (FilesTable.user eq userId) and (FilesTable.folder eq parent) and (FilesTable.name eq name) }
        }
        val fileHit = if (excludeFile != null) files.andWhere { FilesTable.id neq excludeFile }.any() else files.any()
        return folderHit || fileHit
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
        FoldersTable.insert {
            it[FoldersTable.id] = id; it[user] = userId; it[FoldersTable.parent] = parent; it[FoldersTable.name] = name
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
        FoldersTable.update({ FoldersTable.id eq id }) {
            it[name] = newName; it[updatedAt] = System.currentTimeMillis()
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
        val all = mutableListOf(id)
        val queue = ArrayDeque(listOf(id))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            FoldersTable.selectAll().where { FoldersTable.parent eq cur }.forEach { r ->
                val cid = r[FoldersTable.id]; all.add(cid); queue.add(cid)
            }
        }
        // ALL sessions (any status) targeting the subtree reference folders.id by FK;
        // their rows must be removed here or folder row deletion fails.
        val doomedSessionIds = mutableListOf<UUID>()
        for (fid in all) {
            UploadSessionsTable.selectAll().where { UploadSessionsTable.folder eq fid }.forEach { r ->
                doomedSessionIds.add(r[UploadSessionsTable.id])
            }
        }
        for (sid in doomedSessionIds) {
            UploadChunksTable.deleteWhere { UploadChunksTable.session eq sid }
            UploadSessionsTable.deleteWhere { UploadSessionsTable.id eq sid }
        }
        val keys = mutableListOf<String>()
        for (fid in all) {
            FilesTable.selectAll().where { FilesTable.folder eq fid }.forEach { r ->
                keys.add(r[FilesTable.storageKey])
            }
        }
        for (fid in all) FilesTable.deleteWhere { folder eq fid }
        // Delete children before parents so the self-referencing FK (parent_id) is satisfied.
        for (fid in all.asReversed()) FoldersTable.deleteWhere { FoldersTable.id eq fid }
        val blobKeys = keys.distinct().filter { key ->
            FilesTable.selectAll().where { FilesTable.storageKey eq key }.count() == 0L
        }
        FolderDeletion(blobKeys, doomedSessionIds)
    }

    fun getFileMeta(userId: UUID, id: UUID): FileMeta = transaction(DatabaseFactory.db) {
        val r = FilesTable.selectAll().where { (FilesTable.id eq id) and (FilesTable.user eq userId) }.singleOrNull()
            ?: throw ApiException.notFound("文件不存在")
        r.toFileMeta()
    }

    /** Owner-agnostic metadata lookup, only for signature-authenticated access. */
    fun getFileMetaUnscoped(id: UUID): FileMeta = transaction(DatabaseFactory.db) {
        FilesTable.selectAll().where { FilesTable.id eq id }.singleOrNull()?.toFileMeta()
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
        val target: UUID? = when {
            newFolderId == null -> cur.folderId
            newFolderId == "root" -> null
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
        FilesTable.update({ FilesTable.id eq id }) {
            if (newName != null) it[name] = newName
            it[folder] = target
            it[updatedAt] = System.currentTimeMillis()
        }
        val iso = Instant.now().toString()
        FileDto(id.toString(), finalName, target?.toString(), cur.size, cur.mimeType, cur.sha256, iso, iso)
    }

    fun deleteFile(userId: UUID, id: UUID): List<String> = transaction(DatabaseFactory.db) {
        val cur = getFileMeta(userId, id)
        FilesTable.deleteWhere { FilesTable.id eq id }
        if (FilesTable.selectAll().where { FilesTable.storageKey eq cur.storageKey }.count() == 0L) listOf(cur.storageKey) else emptyList()
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
)
