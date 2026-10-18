package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.Throttle
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.core.dto.ShareCreateRequest
import com.linan.barezen_drive.core.dto.ShareDto
import com.linan.barezen_drive.core.dto.SharedContentsResponse
import com.linan.barezen_drive.core.dto.SharedFileDto
import com.linan.barezen_drive.core.dto.SharedFolderDto
import com.linan.barezen_drive.core.dto.SharedInfoResponse
import com.linan.barezen_drive.auth.TokenSecret
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.ShareLinksTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Resolved share: enough context to serve every public endpoint. */
data class ResolvedShare(
    val id: UUID,
    val fileId: UUID?,
    val folderId: UUID?,
)

/** What a public endpoint computed inside its single transaction. */
data class Opened<T>(val share: ResolvedShare, val value: T)

/**
 * The owner's share list, filtered by the database rather than in Kotlin.
 *
 * Revoked and expired rows used to be dropped after the read and the target
 * filter compared `UUID.toString()` against the raw query parameter, so the
 * statement the planner saw was a bare `user_id = ?` and the ordering happened
 * in the heap: every visit to the management screen was a full read of the
 * account's share history plus a sort. Pushing the predicates down lets
 * share_target_idx serve a filtered list, and the ordering is the database's
 * job. The remaining gap is the leading `user_id`, which share_links does not
 * index (see Tables.ShareLinksTable).
 */
internal fun activeShareQuery(
    userId: UUID,
    now: Long,
    fileId: UUID?,
    folderId: UUID?,
): Query {
    val base = ShareLinksTable.selectAll().where {
        (ShareLinksTable.user eq userId) and
            (ShareLinksTable.revokedAt.isNull() and
                // A share with no expiry never lapses; the null branch is the
                // common case for "no TTL", so it has to be inside the SQL.
                (ShareLinksTable.expiresAt.isNull() or (ShareLinksTable.expiresAt greater now)))
    }
    val targeted = when {
        fileId != null -> base.andWhere { ShareLinksTable.file eq fileId }
        folderId != null -> base.andWhere { ShareLinksTable.folder eq folderId }
        else -> base
    }
    return targeted.orderBy(ShareLinksTable.createdAt to SortOrder.DESC)
}

object ShareService {
    private val MIN_TTL_MS = Duration.ofHours(1).toMillis()
    private val MAX_TTL_MS = Duration.ofDays(365).toMillis()
    private const val VIEW_DEDUP_MS = 5 * 60_000L

    /**
     * Page size of a public folder listing, and its ceiling.
     *
     * The endpoint is unauthenticated, so the response size is a cost an anonymous
     * caller gets to choose unless the server refuses. The default is a page nobody
     * scrolls past by accident; the cap bounds what one request may ask for, and a
     * bigger directory is served by the cursor instead of in one heap spike.
     */
    private const val SHARE_PAGE_DEFAULT = 200
    private const val SHARE_PAGE_MAX = 1000


    // ---- Owner operations ----

    /** Creates a share for the given file or folder; returns the DTO carrying the
     *  one-time raw token inside its url. */
    fun createShare(userId: UUID, req: ShareCreateRequest): ShareDto {
        val fileId = req.fileId?.takeIf { it.isNotBlank() }
        val folderId = req.folderId?.takeIf { it.isNotBlank() }
        if ((fileId == null) == (folderId == null)) {
            throw ApiException.badRequest("fileId 与 folderId 必须二选一")
        }
        val ttl = req.expiresInHours
        val expiresAt: Long? = when {
            ttl == null || ttl <= 0 -> null
            else -> {
                // Clamp the hour count *before* multiplying. A huge Long
                // expiresInHours would overflow `ttl * 3_600_000` (and the
                // subsequent sum) and wrap to a past or absurd expiry date.
                // MAX_TTL_MS is exactly 8760h and MIN_TTL_MS exactly 1h, so
                // dividing by the hour keeps the same bounds but expressed in
                // the unit we multiply back in, and bounds the product.
                val hours = ttl.coerceIn(MIN_TTL_MS / 3_600_000L, MAX_TTL_MS / 3_600_000L)
                System.currentTimeMillis() + hours * 3_600_000L
            }
        }
        return transaction(DatabaseFactory.db) {
            val targetType: String
            val targetName: String
            if (fileId != null) {
                val fid = fileId.toUuidOrBadRequest()
                val row = FilesTable.selectAll().where {
                    (FilesTable.id eq fid) and (FilesTable.user eq userId) and (FilesTable.deletedAt eq 0L)
                }.singleOrNull()
                    ?: throw ApiException.notFound("文件不存在")
                targetType = "file"
                targetName = row[FilesTable.name]
            } else {
                val gid = folderId!!.toUuidOrBadRequest()
                val row = FoldersTable.selectAll().where { (FoldersTable.id eq gid) and (FoldersTable.user eq userId) }.singleOrNull()
                    ?: throw ApiException.notFound("文件夹不存在")
                targetType = "folder"
                targetName = row[FoldersTable.name]
            }
            val id = UUID.randomUUID()
            val now = System.currentTimeMillis()
            val token = TokenSecret.mint()
            ShareLinksTable.insert {
                it[ShareLinksTable.id] = id
                it[user] = userId
                it[ShareLinksTable.file] = if (targetType == "file") UUID.fromString(fileId) else null
                it[folder] = if (targetType == "folder") UUID.fromString(folderId) else null
                it[tokenHash] = TokenSecret.hash(token)
                it[createdAt] = now
                it[ShareLinksTable.expiresAt] = expiresAt
            }
            ShareDto(
                id.toString(),
                "/s/$token",
                targetType,
                targetName,
                expiresAt?.let { Instant.ofEpochMilli(it).toString() },
                Instant.ofEpochMilli(now).toString(),
            )
        }
    }

    /** Lists the owner's active (non-revoked, non-expired) shares; no filter
     *  returns every active share for the management screen. */
    fun listShares(userId: UUID, fileId: String?, folderId: String?): List<ShareDto> {
        // A target id that is not a UUID is a client bug, not an empty result.
        // The old code compared UUID.toString() against the raw parameter, so a
        // typo answered "no shares" and read like a lost link; the predicate is
        // a real `file_id = ?` now, and a value that cannot be one is a 400 the
        // same way it is everywhere else in the API.
        val targetFile = fileId?.takeIf { it.isNotBlank() }?.toUuidOrBadRequest()
        val targetFolder = folderId?.takeIf { it.isNotBlank() }?.toUuidOrBadRequest()
        return transaction(DatabaseFactory.db) {
            val rows = activeShareQuery(userId, System.currentTimeMillis(), targetFile, targetFolder).toList()
            // Two batched name lookups instead of one SELECT per share row: the
            // management screen lists every active share, and a per-row query made
            // the endpoint O(n) round trips. A missing row (target deleted) reads
            // as the same "(已删除)" placeholder as before.
            val fileNames = rows.mapNotNull { it[ShareLinksTable.file] }.distinct().let { ids ->
                if (ids.isEmpty()) emptyMap()
                else FilesTable.selectAll().where { FilesTable.id inList ids }
                    .associate { it[FilesTable.id] to it[FilesTable.name] }
            }
            val folderNames = rows.mapNotNull { it[ShareLinksTable.folder] }.distinct().let { ids ->
                if (ids.isEmpty()) emptyMap()
                else FoldersTable.selectAll().where { FoldersTable.id inList ids }
                    .associate { it[FoldersTable.id] to it[FoldersTable.name] }
            }
            rows.map { row ->
                val isFile = row[ShareLinksTable.file] != null
                val targetName = if (isFile) {
                    fileNames[row[ShareLinksTable.file]] ?: "(已删除)"
                } else {
                    folderNames[row[ShareLinksTable.folder]] ?: "(已删除)"
                }
                ShareDto(
                    row[ShareLinksTable.id].toString(),
                    // Token is unrecoverable from the hash; the url carries no secret here.
                    "/s/<token>",
                    if (isFile) "file" else "folder",
                    targetName,
                    row[ShareLinksTable.expiresAt]?.let { Instant.ofEpochMilli(it).toString() },
                    Instant.ofEpochMilli(row[ShareLinksTable.createdAt]).toString(),
                    row[ShareLinksTable.viewCount],
                    row[ShareLinksTable.downloadCount],
                )
            }
        }
    }

    /** Physical row delete: the link stops working immediately. */
    fun revokeShare(userId: UUID, id: UUID) {
        transaction(DatabaseFactory.db) {
            val rows = ShareLinksTable.selectAll().where { (ShareLinksTable.id eq id) and (ShareLinksTable.user eq userId) }
            if (rows.none()) throw ApiException.notFound("分享不存在")
            ShareLinksTable.deleteWhere { (ShareLinksTable.id eq id) and (ShareLinksTable.user eq userId) }
        }
    }

    // ---- Public resolution ----
    //
    // Everything below runs in one transaction per request. It used to be
    // resolveToken + sharedInfo + recordView, three independent transactions in
    // sequence, each taking its own connection from a pool of five while a
    // media player fired a Range request per seek.

    /** Landing page: token -> share context plus its public metadata, in one
     *  transaction, with the per-source view de-duplication folded in. */
    fun openInfo(token: String, viewSource: String?): Opened<SharedInfoResponse> =
        transaction(DatabaseFactory.db) {
            val share = resolveInTransaction(token)
            val info = infoInTransaction(share)
            if (viewSource != null) bumpView(share, viewSource)
            Opened(share, info)
        }

    /**
     * One folder's listing inside a folder share; the requested folder must
     * belong to the share subtree (server-enforced boundary).
     *
     * Paged, and the ordering is the database's job. This is a **public** endpoint:
     * it used to read every folder and every file of the requested directory, sort
     * both in Kotlin and serialise the whole set in one response, so the cost of one
     * unauthenticated request grew with whatever the owner had shared - a heap
     * spike on a 1 GB box was the whole attack, and no client could ask for less.
     * A page is bounded by [limit] and the two cursors say where the next one starts,
     * in the same `(lower(name), id)` order the listing itself uses.
     */
    fun openContents(
        token: String,
        folderParam: String?,
        viewSource: String?,
        limit: Int = SHARE_PAGE_DEFAULT,
        folderCursor: String? = null,
        fileCursor: String? = null,
    ): Opened<SharedContentsResponse> =
        transaction(DatabaseFactory.db) {
            val share = resolveInTransaction(token)
            val rootId = share.folderId ?: throw ApiException.notFound("链接无效或已过期")
            val parent: UUID = when {
                folderParam.isNullOrBlank() || folderParam == "root" -> rootId
                else -> {
                    val fid = folderParam.toUuidOrBadRequest()
                    // Existence and the subtree boundary in one statement: a
                    // folder outside the share simply is not a row here, and
                    // the answer says nothing about whether it exists at all.
                    FoldersTable.selectAll().where {
                        (FoldersTable.id eq fid) and FolderInsideShareTree(rootId, FoldersTable.id)
                    }.singleOrNull() ?: throw ApiException.notFound("文件夹不存在")
                    fid
                }
            }
            val size = limit.coerceIn(1, SHARE_PAGE_MAX)
            // A malformed cursor restarts the page from the top: the worst it can
            // cost a caller is one page it already has.
            val folderAfter = parseNameCursor(folderCursor)
            val fileAfter = parseNameCursor(fileCursor)

            val folders = FoldersTable.select(FoldersTable.id, FoldersTable.name).where {
                (FoldersTable.parent eq parent) and folderAfter.nameAfter(FoldersTable.name, FoldersTable.id)
            }
                .orderBy(FoldersTable.name.lowerCase() to SortOrder.ASC, FoldersTable.id to SortOrder.ASC)
                .limit(size + 1)
                .map { it[FoldersTable.id] to it[FoldersTable.name] }
            // Soft-deleted (trashed) rows stay in `files`, so the public share
            // path must filter them explicitly: a photo the owner moved to the
            // trash may not keep serving through a folder link.
            val files = FilesTable.select(
                FilesTable.id, FilesTable.name, FilesTable.size, FilesTable.mimeType, FilesTable.hasThumbnail,
            ).where {
                (FilesTable.folder eq parent) and (FilesTable.deletedAt eq 0L) and
                    fileAfter.nameAfter(FilesTable.name, FilesTable.id)
            }
                .orderBy(FilesTable.name.lowerCase() to SortOrder.ASC, FilesTable.id to SortOrder.ASC)
                .limit(size + 1)
                .map { it.toSharedFileDto() to it[FilesTable.id] }
            if (viewSource != null) bumpView(share, viewSource)
            // The extra row that decided `hasMore` is not served twice, and the
            // cursor is that last served row: a page boundary lands between rows, so
            // the next one starts where this one stopped.
            val folderPage = folders.take(size)
            val filePage = files.take(size)
            Opened(
                share,
                SharedContentsResponse(
                    folders = folderPage.map { SharedFolderDto(it.first.toString(), it.second) },
                    files = filePage.map { it.first },
                    folderCursor = folderPage.lastOrNull()
                        ?.let { nameCursor(it.second, it.first) }
                        ?.takeIf { folders.size > size },
                    fileCursor = filePage.lastOrNull()
                        ?.let { nameCursor(it.first.name, it.second) }
                        ?.takeIf { files.size > size },
                ),
            )
        }

    /**
     * The keyset condition for a name-ordered page: everything strictly after
     * `(lower(name), id)`, or no condition at all when there is no cursor.
     */
    private fun Pair<String, UUID>?.nameAfter(name: Column<String>, id: Column<UUID>): Op<Boolean> =
        this?.let { NameCursorAfter(name, id, it.first, it.second) } ?: Op.TRUE

    /**
     * `<name>:<id>`, the name sent back as the database received it.
     *
     * The cursor deliberately carries the *raw* name rather than a lowercased one:
     * the comparison applies `lower()` to the parameter too (see [NameCursorAfter]),
     * so both sides are folded by the same rules. A Kotlin `lowercase()` here could
     * disagree with the server's collation for a name like `İstanbul`, and a
     * boundary that disagrees drops or repeats a row - which in a shared folder is a
     * file the visitor never sees.
     */
    private fun nameCursor(name: String, id: UUID): String = "$name:$id"

    /** The id is the tail, so a name containing a colon still parses. */
    private fun parseNameCursor(raw: String?): Pair<String, UUID>? = raw?.let {
        val idx = it.lastIndexOf(':')
        if (idx <= 0) return@let null
        runCatching { it.substring(0, idx) to UUID.fromString(it.substring(idx + 1)) }.getOrNull()
    }

    /**
     * `(lower(name), id) > (lower(?), ?)`.
     *
     * The row form rather than the disjunctive equivalent because only the row
     * comparison becomes an index condition, and both operands are bound as
     * parameters: the name comes from a client.
     */
    private class NameCursorAfter(
        private val name: Column<String>,
        private val id: Column<UUID>,
        private val lastName: String,
        private val lastId: UUID,
    ) : Op<Boolean>() {
        override fun toQueryBuilder(qb: QueryBuilder) {
            qb.append("(lower(")
            name.toQueryBuilder(qb)
            qb.append("), ")
            id.toQueryBuilder(qb)
            qb.append(") > (lower(")
            qb.registerArgument(name.columnType, lastName)
            qb.append("), ")
            qb.registerArgument(id.columnType, lastId)
            qb.append(")")
        }
    }

    /**
     * File access inside a share: file shares match by id, folder shares require
     * the file's folder to sit in the share subtree. [countDownload] folds the
     * download counter into the same transaction the metadata came from, so a
     * download costs one round trip instead of two and the counter can never
     * disagree with the bytes that were actually served.
     */
    fun openFile(token: String, fileId: UUID, countDownload: Boolean): Opened<FileMeta> =
        transaction(DatabaseFactory.db) {
            val share = resolveInTransaction(token)
            // The share boundary is part of the lookup, not a check that follows
            // it: a file share matches on its own id, a folder share matches only
            // rows whose folder sits in the share subtree. Either way the row is
            // found by the database or not at all - one round trip, and no
            // answer that distinguishes "no such file" from "outside the share".
            val boundary: Op<Boolean> = if (share.fileId != null) {
                Op.build { FilesTable.id eq share.fileId }
            } else {
                FolderInsideShareTree(share.folderId ?: throw ApiException.notFound("文件不存在"), FilesTable.folder)
            }
            val row = FilesTable.selectAll().where {
                (FilesTable.id eq fileId) and (FilesTable.deletedAt eq 0L) and boundary
            }.singleOrNull() ?: throw ApiException.notFound("文件不存在")
            if (countDownload) {
                ShareLinksTable.update({ ShareLinksTable.id eq share.id }) {
                    with(SqlExpressionBuilder) {
                        it[downloadCount] = downloadCount + 1
                    }
                }
            }
            Opened(share, row.toFileMeta())
        }

    /** Token -> share context; 404 for unknown/expired/revoked tokens without
     *  distinguishing the reason (no status oracle for guessers). */
    private fun resolveInTransaction(token: String): ResolvedShare {
        val row = ShareLinksTable.selectAll().where { ShareLinksTable.tokenHash eq TokenSecret.hash(token) }.singleOrNull()
            ?: throw ApiException.notFound("链接无效或已过期")
        val now = System.currentTimeMillis()
        if (row[ShareLinksTable.revokedAt] != null) throw ApiException.notFound("链接无效或已过期")
        val exp = row[ShareLinksTable.expiresAt]
        if (exp != null && exp <= now) throw ApiException.notFound("链接无效或已过期")
        return ResolvedShare(row[ShareLinksTable.id], row[ShareLinksTable.file], row[ShareLinksTable.folder])
    }

    private fun infoInTransaction(share: ResolvedShare): SharedInfoResponse {
        if (share.fileId != null) {
            val r = FilesTable.selectAll().where {
                (FilesTable.id eq share.fileId) and (FilesTable.deletedAt eq 0L)
            }.singleOrNull()
                ?: throw ApiException.notFound("链接无效或已过期")
            return SharedInfoResponse(
                "file",
                r[FilesTable.name],
                r[FilesTable.id].toString(),
                r[FilesTable.size],
                r[FilesTable.mimeType],
                Instant.ofEpochMilli(r[FilesTable.updatedAt]).toString(),
            )
        }
        val folderId = share.folderId ?: throw ApiException.notFound("链接无效或已过期")
        val r = FoldersTable.selectAll().where { FoldersTable.id eq folderId }.singleOrNull()
            ?: throw ApiException.notFound("链接无效或已过期")
        return SharedInfoResponse("folder", r[FoldersTable.name])
    }

    /**
     * Bumps the view counter, de-duplicated per source: one visitor refreshing
     * the link must not write the DB every time nor inflate viewCount, so
     * repeats within 5 minutes are collapsed.
     */
    private fun bumpView(share: ResolvedShare, source: String) {
        if (!Throttle.firstSince("shareview:${share.id}:$source", VIEW_DEDUP_MS)) return
        ShareLinksTable.update({ ShareLinksTable.id eq share.id }) {
            with(SqlExpressionBuilder) {
                it[viewCount] = viewCount + 1
            }
        }
    }

    /**
     * `EXISTS (WITH RECURSIVE subtree(id) AS (...) SELECT 1 FROM subtree WHERE id = <folder>)`
     * - true when the folder column's value is [rootId] or below it.
     *
     * The old check walked the parent chain in Kotlin, one SELECT per level,
     * and the walk ran on every request to the public share path - which is
     * every Range request of a video playback - so the price of one thumbnail
     * fetch scaled with how deep somebody had filed their photos. It also
     * carried a 1000-level guard, past which a share of a deep archive quietly
     * answered "not found" for files it does contain. A recursive CTE answers
     * the same question inside the statement that needs the answer, so the
     * whole lookup is one round trip and there is no level ceiling.
     *
     * Being part of the WHERE clause (rather than a query of its own) is also
     * what keeps the 404 uniform: a file outside the share is simply not a row
     * the query returns, exactly like a file that does not exist.
     *
     * UNION, not UNION ALL, so a parent cycle - which the API cannot create but
     * a restored or hand-edited database might - terminates instead of looping.
     */
    private class FolderInsideShareTree(
        private val rootId: UUID,
        private val folder: Expression<*>,
    ) : Op<Boolean>() {
        override fun toQueryBuilder(qb: QueryBuilder) {
            qb.append("EXISTS (WITH RECURSIVE subtree(id) AS (SELECT id FROM folders WHERE id = ")
            qb.registerArgument(FoldersTable.id.columnType, rootId)
            qb.append(" UNION SELECT f.id FROM folders f JOIN subtree s ON f.parent_id = s.id)")
            qb.append(" SELECT 1 FROM subtree WHERE id = ")
            folder.toQueryBuilder(qb)
            qb.append(")")
        }
    }

    private fun ResultRow.toFileMeta() = FileMeta(
        this[FilesTable.id], this[FilesTable.user], this[FilesTable.folder], this[FilesTable.name],
        this[FilesTable.size], this[FilesTable.mimeType], this[FilesTable.sha256], this[FilesTable.storageKey],
        this[FilesTable.hasThumbnail],
    )

    private fun ResultRow.toSharedFileDto() = SharedFileDto(
        this[FilesTable.id].toString(),
        this[FilesTable.name],
        this[FilesTable.size],
        this[FilesTable.mimeType],
        this[FilesTable.hasThumbnail],
    )
}
