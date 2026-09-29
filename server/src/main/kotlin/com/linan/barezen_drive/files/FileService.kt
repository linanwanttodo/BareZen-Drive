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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

data class FileMeta(val id: UUID, val userId: UUID, val folderId: UUID?, val name: String, val size: Long, val mimeType: String?, val sha256: String, val storageKey: String, val hasThumbnail: Boolean)

/** Keys per `IN (...)` batch in the refcount sweep - see orphanBlobKeys. */
private const val REFCOUNT_BATCH = 500

/**
 * Folder ids per `IN (...)` batch in the subtree delete. A level of the tree
 * is one statement, except for a folder with more direct children than this -
 * at which point the bound is the number of bind parameters, not the round
 * trips.
 */
private const val FOLDER_BATCH = 2000

object FileService {
    private const val MILLIS_PER_DAY = 24L * 3600 * 1000

    /** How long a trashed file waits before the cleanup loop purges it. */
    const val TRASH_RETENTION_DAYS = 30L

    private fun nameOk(name: String) = name.isNotBlank() && name.length <= 255 && !name.contains('/')

    /**
     * `column IS NULL` for a null [parent] (the account root) and `column = ?`
     * for a real folder id.
     *
     * Every "which rows live here" filter used to be written twice - once per
     * shape - so each of them (the live-only predicate, the exclude-self
     * clause, the name check) was a place a fix had to land twice and could
     * be fixed on one side only. One parameterized predicate keeps the root
     * and nested paths from drifting apart.
     */
    private fun scopedTo(column: Column<UUID?>, parent: UUID?): Op<Boolean> =
        Op.build { if (parent == null) column.isNull() else column.eq(parent) }

    /**
     * Folder listing. [limit] opts into paging the file list: without it the
     * whole folder comes back, which is what the album's category and platform
     * lookups expect. With it, files are cut at [limit] in the same name order
     * the unpaged answer used, and the last row's "<name-lowercased>:<id>" is
     * handed back as [ContentsResponse.nextCursor] - the cursor shape the album
     * endpoint already uses, so a file renamed mid-scroll is skipped or repeated
     * by at most one row instead of vanishing.
     *
     * Folders are never paged: there are orders of magnitude fewer of them, and
     * a client that could not see a folder would be worse off than one that
     * re-requests files.
     */
    fun contents(userId: UUID, folderId: String, limit: Int? = null, cursor: String? = null): ContentsResponse =
        transaction(DatabaseFactory.db) {
            val parent: UUID? = if (folderId == "root") null else {
                val id = folderId.toUuidOrBadRequest()
                FoldersTable.selectAll().where { (FoldersTable.id eq id) and (FoldersTable.user eq userId) }.singleOrNull()
                    ?: throw ApiException.notFound("文件夹不存在")
                id
            }
            val folders = FoldersTable.selectAll()
                .where { (FoldersTable.user eq userId) and scopedTo(FoldersTable.parent, parent) }
                .map { it.toFolderDto() }
            var q = FilesTable.selectAll()
                .where { (FilesTable.user eq userId) and scopedTo(FilesTable.folder, parent) and (FilesTable.deletedAt eq 0L) }
            // A malformed cursor restarts from the top, same as the album page:
            // better an extra page than an empty folder.
            val after = cursor?.let { parseFileCursor(it) }
            if (after != null) {
                val (name, id) = after
                val nameCol = FilesTable.name.lowerCase()
                q = q.andWhere {
                    (nameCol greater name) or ((nameCol eq name) and (FilesTable.id greater id))
                }
            }
            val ordered = q.orderBy(FilesTable.name.lowerCase() to SortOrder.ASC, FilesTable.id to SortOrder.ASC)
            val rows = if (limit == null) {
                ordered.map { it.toFileDto() }
            } else {
                // limit + 1 rows so "is there more" is answered by the query
                // rather than by a second count.
                ordered.limit(limit + 1).map { it.toFileDto() }
            }
            val page = if (limit != null && rows.size > limit) rows.take(limit) else rows
            val next = if (limit != null && rows.size > limit && page.isNotEmpty()) {
                val last = page.last()
                "${last.name.lowercase()}:${last.id}"
            } else {
                null
            }
            val cur = parent?.let { pid -> FoldersTable.selectAll().where { FoldersTable.id eq pid }.single().toFolderDto() }
            ContentsResponse(cur, folders.sortedBy { it.name.lowercase() }, page, next)
        }

    /** "<name-lowercased>:<uuid>"; null when the value does not parse. */
    private fun parseFileCursor(raw: String): Pair<String, UUID>? {
        val cut = raw.lastIndexOf(':')
        if (cut <= 0) return null
        val id = runCatching { UUID.fromString(raw.substring(cut + 1)) }.getOrNull() ?: return null
        return raw.substring(0, cut) to id
    }

    /** Folders and files share one sibling namespace: a clash on either side is a conflict.
     *  Trashed rows are not part of the namespace (see the live-only filters inside). */
    internal fun nameConflict(userId: UUID, parent: UUID?, name: String, excludeFolder: UUID? = null, excludeFile: UUID? = null): Boolean {
        val folders = FoldersTable.selectAll()
            .where { (FoldersTable.user eq userId) and scopedTo(FoldersTable.parent, parent) and (FoldersTable.name eq name) }
        val folderHit = if (excludeFolder != null) folders.andWhere { FoldersTable.id neq excludeFolder }.any() else folders.any()
        // A trashed row does not hold the name: the same file can be uploaded
        // again while the old copy waits in the trash.
        val files = FilesTable.selectAll()
            .where { (FilesTable.user eq userId) and scopedTo(FilesTable.folder, parent) and (FilesTable.name eq name) and (FilesTable.deletedAt eq 0L) }
        val fileHit = if (excludeFile != null) files.andWhere { FilesTable.id neq excludeFile }.any() else files.any()
        return folderHit || fileHit
    }

    /** Live (non-trashed) file row holding [name] under [parent], if any. The
     *  overwrite upload path replaces exactly this row; a folder of the same
     *  name is a different animal and never a replace target. */
    internal fun findLiveFile(userId: UUID, parent: UUID?, name: String): ResultRow? =
        FilesTable.selectAll()
            .where { (FilesTable.user eq userId) and scopedTo(FilesTable.folder, parent) and (FilesTable.name eq name) and (FilesTable.deletedAt eq 0L) }
            .firstOrNull()

    internal fun hasFolderNamed(userId: UUID, parent: UUID?, name: String): Boolean =
        FoldersTable.selectAll()
            .where { (FoldersTable.user eq userId) and scopedTo(FoldersTable.parent, parent) and (FoldersTable.name eq name) }
            .any()

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

    /** One page of the trash: the rows, plus the cursor for the next older page
     *  (null when this was the last one). */
    data class TrashPage(val files: List<FileDto>, val nextCursor: String?)

    /** Recursively delete the folder subtree; returns storage keys whose blob refcount drops to zero. */
    fun deleteFolder(userId: UUID, id: UUID): FolderDeletion = transaction(DatabaseFactory.db) {
        FoldersTable.selectAll().where { (FoldersTable.id eq id) and (FoldersTable.user eq userId) }.singleOrNull()
            ?: throw ApiException.notFound("文件夹不存在")
        // The whole subtree from one query, grouped by depth. Level 0 is the
        // root itself, level 1 its children, and so on - the shape the delete
        // below needs to honour the parent FK without one statement per folder.
        val levels = subtreeLevels(userId, id)
        val all = levels.flatten()
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
        // Deepest level first: the self-referencing FK (parent_id) forbids
        // removing a parent while a child row still points at it. One
        // `WHERE id IN (...)` per level instead of one per folder - the old
        // per-folder loop turned a 1641-folder tree into 1641 round trips, all
        // of them holding one of the 5 pooled connections.
        for (level in levels.asReversed()) {
            for (batch in level.chunked(FOLDER_BATCH)) {
                val op = SqlExpressionBuilder.run { FoldersTable.id inList batch }
                FoldersTable.deleteWhere { op }
            }
        }
        FolderDeletion(orphanBlobKeys(keys), doomedSessionIds)
    }

    /**
     * The subtree rooted at [rootId], grouped into breadth-first levels
     * (index 0 = the root). One query for the account's whole folder set,
     * matched by the walk `FileTree` already performs, so the grouping costs
     * no extra round trip over the flat id list.
     */
    private fun subtreeLevels(userId: UUID, rootId: UUID): List<List<UUID>> {
        val childrenByParent = HashMap<UUID, MutableList<UUID>>()
        FoldersTable.select(FoldersTable.id, FoldersTable.parent)
            .where { FoldersTable.user eq userId }
            .forEach { row ->
                row[FoldersTable.parent]?.let { parent ->
                    childrenByParent.getOrPut(parent) { mutableListOf() }.add(row[FoldersTable.id])
                }
            }
        val levels = mutableListOf<MutableList<UUID>>()
        val seen = HashSet<UUID>()
        var level = listOf(rootId)
        while (level.isNotEmpty()) {
            // A cycle in the parent pointers (only reachable through a manual
            // DB edit) would otherwise spin here forever.
            if (!seen.addAll(level)) break
            levels += level.toMutableList()
            level = level.flatMap { childrenByParent[it].orEmpty() }
        }
        return levels
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
        val cur = fileRowOf(userId, id)
        if (newName != null) {
            if (!nameOk(newName)) throw ApiException.badRequest("名称非法")
        }
        val target: UUID? = when (newFolderId) {
            null -> cur[FilesTable.folder]
            "root" -> null
            else -> {
                val tid = newFolderId.toUuidOrBadRequest()
                FoldersTable.selectAll().where { (FoldersTable.id eq tid) and (FoldersTable.user eq userId) }.singleOrNull()
                    ?: throw ApiException.notFound("目标文件夹不存在")
                tid
            }
        }
        val finalName = newName ?: cur[FilesTable.name]
        // Files have no subtree, so no into-descendant check is needed; only the sibling name clash.
        if (nameConflict(userId, target, finalName, excludeFile = id)) {
            throw ApiException.conflict(ErrorCodes.NAME_CONFLICT, "目标位置已存在同名文件夹或文件")
        }
        val now = System.currentTimeMillis()
        val touched = mapNameConflict {
            FilesTable.update({ FilesTable.id eq id }) {
                if (newName != null) it[name] = newName
                it[folder] = target
                it[updatedAt] = now
            }
        }
        requireTouched(touched)
        cur.toFileDto().copy(
            name = finalName,
            folderId = target?.toString(),
            updatedAt = iso(now),
        )
    }

    /**
     * Soft delete: the row keeps existing (so its blob still has a reference and
     * the id stays restorable) and only gains a trash timestamp. Physical
     * removal happens through the trash endpoints or the retention sweep.
     */
    fun trashFile(userId: UUID, id: UUID): FileDto = transaction(DatabaseFactory.db) {
        val row = fileRowOf(userId, id)
        val now = System.currentTimeMillis()
        val touched = FilesTable.update({ FilesTable.id eq id }) {
            it[deletedAt] = now
            it[updatedAt] = now
        }
        // A trashed file must stop serving its public links immediately. They are
        // revoked rather than deleted (view/download counters survive), and
        // restoring the file does not silently re-publish them: re-sharing stays
        // an explicit act.
        val activeLinks = SqlExpressionBuilder.run { (ShareLinksTable.file eq id) and ShareLinksTable.revokedAt.isNull() }
        ShareLinksTable.update({ activeLinks }) { it[revokedAt] = now }
        // The row was read one statement ago and only these two columns moved,
        // so the DTO comes from that read plus what the write set - no third
        // round trip re-reading the primary key the first statement had.
        requireTouched(touched)
        row.toFileDto().copy(deletedAt = iso(now), updatedAt = iso(now))
    }

    /**
     * One page of the trash, most recently trashed first.
     *
     * Paged rather than "everything": emptying ten thousand photos and then
     * opening the trash screen used to build ten thousand 12-field DTOs with
     * three timestamps stringified each, in one response and one unbounded
     * list. The keyset is (deleted_at DESC, id DESC) - id breaks the ties a
     * bulk trash creates, where hundreds of rows share one millisecond, so the
     * walk cannot stall or repeat on them.
     *
     * [limit] is clamped by the caller-facing route; a malformed [cursor]
     * restarts from the top, same as the album and contents pages.
     */
    fun listTrash(userId: UUID, limit: Int, cursor: String? = null): TrashPage = transaction(DatabaseFactory.db) {
        var q = FilesTable.selectAll().where { (FilesTable.user eq userId) and (FilesTable.deletedAt greater 0L) }
        cursor?.let { parseTrashCursor(it) }?.let { (ms, id) ->
            q = q.andWhere {
                (FilesTable.deletedAt less ms) or ((FilesTable.deletedAt eq ms) and (FilesTable.id less id))
            }
        }
        // limit + 1 rows: "is there more" is answered by the query, not by a
        // second count over the trash.
        val rows = q.orderBy(FilesTable.deletedAt to SortOrder.DESC, FilesTable.id to SortOrder.DESC)
            .limit(limit + 1)
            .map { it[FilesTable.deletedAt] to it.toFileDto() }
        val page = rows.take(limit)
        val more = rows.size > limit
        TrashPage(
            page.map { it.second },
            if (more && page.isNotEmpty()) "${page.last().first}:${page.last().second.id}" else null,
        )
    }

    /** "<deletedAtMillis>:<uuid>"; null when the value does not parse. */
    private fun parseTrashCursor(raw: String): Pair<Long, UUID>? {
        val cut = raw.lastIndexOf(':')
        if (cut <= 0) return null
        val ms = raw.substring(0, cut).toLongOrNull() ?: return null
        val id = runCatching { UUID.fromString(raw.substring(cut + 1)) }.getOrNull() ?: return null
        return ms to id
    }

    /** Puts a trashed file back on the shelf. A live sibling already holding the
     *  name is a 409, so the client can rename and retry instead of losing rows. */
    fun restoreFile(userId: UUID, id: UUID): FileDto = transaction(DatabaseFactory.db) {
        val row = fileRowOf(userId, id)
        if (row[FilesTable.deletedAt] == 0L) return@transaction row.toFileDto()
        if (nameConflict(userId, row[FilesTable.folder], row[FilesTable.name], excludeFile = id)) {
            throw ApiException.conflict(ErrorCodes.NAME_CONFLICT, "目标位置已存在同名文件夹或文件")
        }
        val now = System.currentTimeMillis()
        val touched = mapNameConflict {
            FilesTable.update({ FilesTable.id eq id }) {
                it[deletedAt] = 0L
                it[updatedAt] = now
            }
        }
        requireTouched(touched)
        row.toFileDto().copy(deletedAt = null, updatedAt = iso(now))
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
        val row = fileRowOf(userId, id)
        val touched = FilesTable.update({ FilesTable.id eq id }) { it[isFavorite] = favorite }
        requireTouched(touched)
        row.toFileDto().copy(isFavorite = favorite)
    }

    /** Archive is a flag, not a move: the file keeps its folder and name. */
    fun setArchived(userId: UUID, id: UUID, archived: Boolean): FileDto = transaction(DatabaseFactory.db) {
        val row = fileRowOf(userId, id)
        val now = System.currentTimeMillis()
        val touched = FilesTable.update({ FilesTable.id eq id }) {
            it[archivedAt] = if (archived) now else 0L
            it[updatedAt] = now
        }
        requireTouched(touched)
        row.toFileDto().copy(archivedAt = if (archived) iso(now) else null, updatedAt = iso(now))
    }

    /**
     * The UPDATE matched no row even though the row was just read: only possible
     * if something deleted it in between, which the pre-read could not see.
     * Reported as the same 404 the trailing re-read used to answer, so the
     * round-trip saving never turns a 404 into a 200 with a phantom DTO.
     */
    private fun requireTouched(touched: Int) {
        if (touched == 0) throw ApiException.notFound("文件不存在")
    }

    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

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
     *
     * Batched, not per key: this runs twice per delete chain (hardDelete, then
     * the post-commit purge recount), so the per-key form turned a 1000-blob
     * folder delete into 2000-4000 sequential round trips on a 5-connection
     * pool. One `IN (...)` per table per batch answers the same question, and
     * with the storage_key indexes it is an index-only scan. The batches exist
     * because the parameter count is bounded - 500 varchar(512) parameters is
     * well inside PostgreSQL's 65535 limit, and keeps the statement small
     * enough that the planner still picks the index.
     */
    internal fun orphanBlobKeys(candidateKeys: List<String>): List<String> {
        if (candidateKeys.isEmpty()) return emptyList()
        val keys = candidateKeys.distinct()
        val stillReferenced = HashSet<String>(keys.size)
        for (batch in keys.chunked(REFCOUNT_BATCH)) {
            val inFiles = SqlExpressionBuilder.run { FilesTable.storageKey inList batch }
            FilesTable.select(FilesTable.storageKey).where { inFiles }
                .forEach { stillReferenced += it[FilesTable.storageKey] }
            val inVersions = SqlExpressionBuilder.run { FileVersionsTable.storageKey inList batch }
            FileVersionsTable.select(FileVersionsTable.storageKey).where { inVersions }
                .forEach { stillReferenced += it[FileVersionsTable.storageKey] }
        }
        return keys.filter { it !in stillReferenced }
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
