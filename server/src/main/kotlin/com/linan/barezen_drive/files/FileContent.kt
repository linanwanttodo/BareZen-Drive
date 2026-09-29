package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.auth.LinkService
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.AlbumPage
import com.linan.barezen_drive.core.dto.FileLinkResponse
import com.linan.barezen_drive.core.dto.RecentFilesResponse
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.ExpressionWithColumnType
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.QueryBuilder

fun Route.fileContentRoutes(storage: StorageProvider) {
    get("/api/search") {
        // Name search across everything the account owns (files page, search
        // tab). Case-insensitive substring, newest first, hard cap 100.
        val q = call.request.queryParameters["q"]?.trim().orEmpty()
        val items = if (q.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                transaction(DatabaseFactory.db) {
                    // Escape LIKE metacharacters: a query of "%" should find a
                    // literal percent sign, not "every file".
                    val like = q.lowercase()
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                    FilesTable.selectAll()
                        .where {
                            (FilesTable.user eq call.userId) and
                                (FilesTable.deletedAt eq 0L) and
                                (FilesTable.archivedAt eq 0L) and
                                FilesTable.name.lowerCase().like(LikePattern("%$like%", '\\'))
                        }
                        .orderBy(FilesTable.updatedAt, SortOrder.DESC)
                        .limit(100)
                        .map { it.toFileDto() }
                }
            }
        }
        call.respond(RecentFilesResponse(items))
    }

    get("/api/files/recent") {
        // Most recently touched files for the home screen ("recent" tab). Distinct by
        // name+folder is not required for v0.0.1: rows are per-file metadata, sorted by
        // the epoch-millis updated_at maintained on upload/rename/move.
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()
            ?.coerceIn(1, 50) ?: 12
        val items = withContext(Dispatchers.IO) {
            transaction(DatabaseFactory.db) {
                FilesTable.selectAll()
                    .where {
                        (FilesTable.user eq call.userId) and
                            (FilesTable.deletedAt eq 0L) and
                            (FilesTable.archivedAt eq 0L)
                    }
                    .orderBy(FilesTable.updatedAt, SortOrder.DESC)
                    .limit(limit)
                    .map { it.toFileDto() }
            }
        }
        call.respond(RecentFilesResponse(items))
    }

    // Strictly authenticated: mints a capability URL for agents that cannot send
    // headers (browser tabs, players). The route block itself runs under optional
    // auth, so call.userId enforces the requirement here.
    get("/api/files/{id}/link") {
        val id = call.parameters["id"]!!.toUuidOrBadRequest()
        val meta = withContext(Dispatchers.IO) { FileService.getFileMeta(call.userId, id) }
        val ttl = call.request.queryParameters["ttl"]?.toIntOrNull()?.coerceIn(30, 3600) ?: 300
        val exp = System.currentTimeMillis() / 1000 + ttl
        val sig = LinkService.sign(meta.id, exp)
        call.respond(FileLinkResponse("/api/files/${meta.id}/content?exp=$exp&sig=$sig", Instant.ofEpochMilli(exp * 1000).toString()))
    }

    // Album order: capture time when known, else upload time.
    get("/api/album") {
        // Media timeline: images and videos, newest first, keyset pagination on
        // (updated_at, id). Grouping is a client-side concern (local timezone).
        // Optional root=<folderId> scopes the scan to that folder's subtree - the
        // client uses it to show only the dedicated album folder tree.
        // category filters like a phone gallery: all (default), image, video,
        // screenshot (filename heuristic - screenshots keep their camera-app names).
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 200
        val cursor = call.request.queryParameters["before"]
        val rootParam = call.request.queryParameters["root"]
        val category = (call.request.queryParameters["category"] ?: "all").lowercase()
        // Gallery filters: the timeline hides archived rows unless the caller
        // asks for them, and "favorite" narrows to the starred subset. Trashed
        // rows are never part of any of these views.
        val favoriteOnly = call.flagParam("favorite")
        val archivedOnly = call.flagParam("archived")
        // A malformed cursor just restarts from the top.
        val (cursorTs, cursorId) = cursor?.let { parseCursor(it) } ?: (null to null)
        val rootId = rootParam?.toUuidOrBadRequest()
        val rows = withContext(Dispatchers.IO) {
            transaction(DatabaseFactory.db) {
                // Resolved inside the transaction: the subtree is a query of its own.
                val subtree = rootId?.let { FileTree.descendants(call.userId, it) } ?: emptyList()
                albumPageQuery(
                    userId = call.userId,
                    mediaFilter = albumMediaFilter(category),
                    favoriteOnly = favoriteOnly,
                    archivedOnly = archivedOnly,
                    rootId = rootId,
                    subtree = subtree,
                    cursorTs = cursorTs,
                    cursorId = cursorId,
                )
                    .limit(limit + 1)
                    .map { (it[FilesTable.takenAt] ?: it[FilesTable.updatedAt]) to it.toFileDto() }
            }
        }
        val hasMore = rows.size > limit
        val page = rows.take(limit)
        val nextCursor = if (hasMore && page.isNotEmpty()) {
            val (ms, dto) = page.last()
            "$ms:${dto.id}"
        } else {
            null
        }
        call.respond(AlbumPage(page.map { it.second }, nextCursor))
    }

    get("/api/files/{id}/content") {
        val id = call.parameters["id"]!!.toUuidOrBadRequest()
        // Either a valid Bearer (scoped to the owner) or a valid signature grants access.
        val meta = if (call.signedFor(id)) {
            withContext(Dispatchers.IO) { FileService.getFileMetaUnscoped(id) }
        } else {
            withContext(Dispatchers.IO) { FileService.getFileMeta(call.userId, id) }
        }
        // Content-type policy, the pruned-blob 404 and the open-once-then-close
        // streaming all live in one place, shared with the public share route:
        // see respondStoredFile.
        call.respondStoredFile(storage, meta)
    }
}

/** True when the query flag [name] is present as "true" (any case). Absent or
 *  any other value reads as false, so a stale client keeps the default view. */
private fun ApplicationCall.flagParam(name: String): Boolean =
    request.queryParameters[name]?.equals("true", ignoreCase = true) == true

private fun parseCursor(cursor: String): Pair<Long, UUID>? = runCatching {    val idx = cursor.lastIndexOf(':')
    cursor.substring(0, idx).toLong() to UUID.fromString(cursor.substring(idx + 1))
}.getOrNull()

/** Album ordering key: capture time when known, else upload time. */
private fun albumSortTs(): ExpressionWithColumnType<Long> =
    object : ExpressionWithColumnType<Long>() {
        override val columnType = LongColumnType()
        override fun toQueryBuilder(qb: QueryBuilder) {
            qb.append("COALESCE(")
            FilesTable.takenAt.toQueryBuilder(qb)
            qb.append(", ")
            FilesTable.updatedAt.toQueryBuilder(qb)
            qb.append(")")
        }
    }

/** Media-kind filter for the album timeline; a gallery selector in the UI. */
private fun albumMediaFilter(category: String): Op<Boolean> = when (category) {
    "image" -> Op.build { FilesTable.mimeType.like("image/%") }
    "video" -> Op.build { FilesTable.mimeType.like("video/%") }
    // Screenshot is matched by the common camera-app names; the
    // escaped literals are ?? (CJK filesystems keep them).
    "screenshot" -> Op.build {
        FilesTable.name.like("%creenshot%") or FilesTable.name.like("%截图%")
    }
    else -> Op.build { FilesTable.mimeType.like("image/%") or FilesTable.mimeType.like("video/%") }
}

/**
 * The album timeline query, in the order files_album_sort_idx describes:
 * (user_id, COALESCE(taken_at, updated_at) DESC, id DESC). The trailing id is
 * what makes the keyset cursor a total order, so a page boundary lands between
 * two rows and the next page resumes exactly where this one stopped.
 *
 * The cursor is a single row comparison rather than the equivalent
 * `(sort_ts < ?) OR (sort_ts = ? AND id < ?)` because only the row form becomes
 * an index condition. The disjunctive form is a plain filter: PostgreSQL still
 * orders by the index, but starts at its head and discards every row above the
 * cursor, so page N of a scroll re-reads pages 1..N-1. Same rows, same order,
 * one scan instead of N (50k-row library, EXPLAIN ANALYZE: 4.73 ms / 937
 * buffers for the OR form against 0.12 ms / 12 buffers for the row form).
 */
internal fun albumPageQuery(
    userId: UUID,
    mediaFilter: Op<Boolean>,
    favoriteOnly: Boolean,
    archivedOnly: Boolean,
    rootId: UUID?,
    subtree: List<UUID>,
    cursorTs: Long?,
    cursorId: UUID?,
): Query {
    var q = FilesTable.selectAll().where {
        (FilesTable.user eq userId) and mediaFilter and (FilesTable.deletedAt eq 0L) and
            (if (archivedOnly) (FilesTable.archivedAt greater 0L) else (FilesTable.archivedAt eq 0L))
    }
    if (favoriteOnly) q = q.andWhere { FilesTable.isFavorite eq true }
    if (rootId != null) {
        q = q.andWhere { (FilesTable.folder inList subtree) or (FilesTable.folder eq rootId) }
    }
    if (cursorTs != null && cursorId != null) {
        q = q.andWhere { AlbumCursorSeek(cursorTs, cursorId) }
    }
    return q.orderBy(albumSortTs() to SortOrder.DESC, FilesTable.id to SortOrder.DESC)
}

/**
 * `(sort_ts, id) < (?, ?)` on the album sort key. Exposed has no row-comparison
 * expression, so it is spelled out; the argument order has to match
 * files_album_sort_idx exactly for the planner to turn it into an index seek.
 */
private class AlbumCursorSeek(private val ts: Long, private val id: UUID) : Op<Boolean>() {
    override fun toQueryBuilder(qb: QueryBuilder) {
        qb.append("(")
        albumSortTs().toQueryBuilder(qb)
        qb.append(", ")
        FilesTable.id.toQueryBuilder(qb)
        qb.append(") < (")
        // Bound parameters, not literals: both come from the request, and the
        // row form only becomes an index condition with parameters in place.
        qb.registerArgument(LongColumnType(), ts)
        qb.append(", ")
        qb.registerArgument(FilesTable.id.columnType, id)
        qb.append(")")
    }
}

/**
 * True when the call carries a signature that binds exactly this file id
 * (the caller has no valid JWT principal). Under optional authentication an
 * absent/invalid Bearer leaves the principal null and the decision falls to
 * the signature.
 */
internal fun ApplicationCall.signedFor(fileId: UUID): Boolean {
    if (principal<JWTPrincipal>() != null) return false
    val exp = request.queryParameters["exp"]?.toLongOrNull() ?: return false
    val sig = request.queryParameters["sig"] ?: return false
    return LinkService.verify(fileId, exp, sig)
}
