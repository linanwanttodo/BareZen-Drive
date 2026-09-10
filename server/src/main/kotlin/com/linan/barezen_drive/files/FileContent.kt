package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.auth.LinkService
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.AlbumPage
import com.linan.barezen_drive.core.dto.FileLinkResponse
import com.linan.barezen_drive.core.dto.RecentFilesResponse
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

fun Route.fileContentRoutes(storage: StorageProvider) {
    get("/api/files/recent") {
        // Most recently touched files for the home screen ("recent" tab). Distinct by
        // name+folder is not required for v0.0.1: rows are per-file metadata, sorted by
        // the epoch-millis updated_at maintained on upload/rename/move.
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()
            ?.coerceIn(1, 50) ?: 12
        val items = withContext(Dispatchers.IO) {
            transaction(DatabaseFactory.db) {
                FilesTable.selectAll()
                    .where { FilesTable.user eq call.userId }
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

    get("/api/album") {
        // Photo timeline: images only, newest first, keyset pagination on
        // (updated_at, id). Month grouping is a client-side concern (local timezone).
        // Optional root=<folderId> scopes the scan to that folder's subtree - the
        // client uses it to show only the dedicated album folder tree.
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 200
        val cursor = call.request.queryParameters["before"]
        val rootParam = call.request.queryParameters["root"]
        val rows = withContext(Dispatchers.IO) {
            transaction(DatabaseFactory.db) {
                var q = if (rootParam != null) {
                    val rootId = rootParam.toUuidOrBadRequest()
                    val subtree = collectSubtreeIds(call.userId, rootId)
                    FilesTable.selectAll()
                        .where { (FilesTable.user eq call.userId) and FilesTable.mimeType.like("image/%") }
                        .andWhere { (FilesTable.folder inList subtree) or (FilesTable.folder eq rootId) }
                } else {
                    FilesTable.selectAll()
                        .where { (FilesTable.user eq call.userId) and FilesTable.mimeType.like("image/%") }
                }
                if (cursor != null) {
                    // Format "<epochMillis>:<uuid>"; a malformed cursor just restarts from the top.
                    parseCursor(cursor)?.let { (ms, id) ->
                        q = q.andWhere {
                            (FilesTable.updatedAt less ms) or
                                ((FilesTable.updatedAt eq ms) and (FilesTable.id less id))
                        }
                    }
                }
                q.orderBy(FilesTable.updatedAt, SortOrder.DESC)
                    .limit(limit + 1)
                    .map { it[FilesTable.updatedAt] to it.toFileDto() }
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
        // mimeType is stored verbatim from the client; a malformed value must not turn
        // every download of this file into a 500. Fall back to octet-stream instead.
        val contentType = meta.mimeType?.let { mime ->
            runCatching { ContentType.parse(mime) }.getOrNull()
        } ?: ContentType.Application.OctetStream
        // Range handling is delegated to the installed PartialContent plugin: no Range
        // header stays a plain 200; a byte range becomes 206 + Content-Range; an
        // unsatisfiable range becomes 416 with "bytes */total". The plugin slices the
        // channel lazily, so range requests never buffer the whole file in memory.
        call.respond(FileStream(withContext(Dispatchers.IO) { storage.get(meta.storageKey) }, meta.size, contentType))
    }
}

private fun parseCursor(cursor: String): Pair<Long, UUID>? = runCatching {
    val idx = cursor.lastIndexOf(':')
    cursor.substring(0, idx).toLong() to UUID.fromString(cursor.substring(idx + 1))
}.getOrNull()

/** All folder ids inside [rootId]'s subtree (root excluded, children included),
 *  built from a single query over the user's folders instead of one query per
 *  ancestor - album pagination hits this on every page load. */
private fun collectSubtreeIds(userId: UUID, rootId: UUID): List<UUID> {
    val childrenByParent = HashMap<UUID, MutableList<UUID>>()
    FoldersTable.selectAll().where { FoldersTable.user eq userId }.forEach { r ->
        r[FoldersTable.parent]?.let { p ->
            childrenByParent.getOrPut(p) { mutableListOf() }.add(r[FoldersTable.id])
        }
    }
    val ids = mutableListOf<UUID>()
    val queue = ArrayDeque(listOf(rootId))
    while (queue.isNotEmpty()) {
        for (child in childrenByParent[queue.removeFirst()].orEmpty()) {
            ids.add(child)
            queue.add(child)
        }
    }
    return ids
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
