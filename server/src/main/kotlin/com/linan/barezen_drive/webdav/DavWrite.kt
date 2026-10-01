package com.linan.barezen_drive.webdav

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.files.FileService
import com.linan.barezen_drive.files.deleteStoredBlobs
import com.linan.barezen_drive.storage.StorageProvider
import com.linan.barezen_drive.storage.StorageRegistry
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.method
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.net.URI
import java.util.UUID

/**
 * MKCOL, MOVE, COPY and DELETE on a mount.
 *
 * These four are what turns a read-only view into a drive somebody actually
 * uses: a client that cannot create a folder, drag one in, or delete one is a
 * read-only export with extra steps. Every route here delegates the row work to
 * [FileService] and answers with the status RFC 4918 asks for, and none of them
 * re-implements what the app's own REST routes already do with the same rows -
 * a mount DELETE of a file is the app's trash, and a mount DELETE of a folder is
 * the app's folder delete, so the two surfaces cannot drift apart.
 */

/**
 * Registers the four methods.
 *
 * `method(HttpMethod("MKCOL")) { handle { ... } }` and never `mkcol { handle {
 * ... } }`: Ktor's `mkcol(body)` helper (like `get`/`put`) is itself
 * `method(...) { handle(body) }`, so passing a handler that installs another
 * handler produces a route whose body never runs and falls through to the
 * catch-all 404 - which reads exactly like "this method is not implemented".
 * `handle` is a member of [Route], so it is not imported.
 */
internal fun Route.davWriteMethods() {
    method(HttpMethod("MKCOL")) { handle { call.respondMkcol() } }
    method(HttpMethod("MOVE")) { handle { call.respondDavMoveCopy(copying = false) } }
    method(HttpMethod("COPY")) { handle { call.respondDavMoveCopy(copying = true) } }
    method(HttpMethod("DELETE")) { handle { call.respondDavDelete() } }
}

/** A missing intermediate collection: the request is well formed, the path is not. */
private const val DAV_MISSING_PARENT = "dav-missing-parent"

/** The method exists but is not defined for this resource type. */
private const val DAV_METHOD_NOT_ALLOWED = "dav-method-not-allowed"

/** A `Destination` we cannot reduce to a path on this server's own /dav surface. */
private const val DAV_BAD_DESTINATION = "dav-bad-destination"

private const val DAV_BAD_OVERWRITE = "dav-overwrite-invalid"

/** A destination that would sit inside the source, or contain it. */
private const val DAV_INTO_SELF = "dav-into-self"

/** A body on a method whose definition has none. */
private const val DAV_BODY_NOT_ALLOWED = "dav-body-not-allowed"

/**
 * How much of a MKCOL body is read before it is refused.
 *
 * Bounded because the check is "is there any body at all", so there is nothing
 * to gain from buffering an arbitrary one; this only decides whether the client
 * gets its 415 without the server first accepting its whole upload.
 */
private const val MAX_MKCOL_BODY = 64 * 1024

// ---- MKCOL -------------------------------------------------------------------

/**
 * MKCOL, answered with the status RFC 4918 §8.3.1 asks for.
 *
 * A body is 415 rather than ignored: the extended MKCOL of RFC 5689 (and the
 * many clients that send one) is not implemented here, and silently accepting it
 * would let a client believe its request had been applied.
 *
 * An existing resource is 405 and not "replaced": siblings share one namespace,
 * so MKCOL onto a file name must not replace a file with a collection, and onto
 * a folder must not silently adopt a subtree.
 */
internal suspend fun ApplicationCall.respondMkcol() {
    davRequireQuota()
    val userId = davWritable().userId
    val segments = davSegments(request.path().removePrefix("/dav"))
        ?: throw ApiException.notFound("资源不存在")
    if (request.receiveChannel().readRemaining(MAX_MKCOL_BODY + 1L).readByteArray().isNotEmpty()) {
        throw ApiException(DAV_BODY_NOT_ALLOWED, HttpStatusCode.UnsupportedMediaType, "MKCOL 不接受请求体")
    }
    // The account root is not a row, so it can neither be created nor replaced.
    if (segments.isEmpty()) {
        throw ApiException(DAV_METHOD_NOT_ALLOWED, HttpStatusCode.MethodNotAllowed, "根集合已存在")
    }
    val name = segments.last()
    // 409 for a missing intermediate collection, the same answer PUT gives: the
    // client must change the path, not retry. RFC 4918 would also let one MKCOL
    // create a whole chain of missing intermediates; we refuse that, because
    // one MKCOL naming one collection is what every client actually sends, and
    // the difference is invisible except to a client that would get a tree it
    // did not ask for.
    val parent = when (val p = resolveDavPath(userId, segments.dropLast(1))) {
        is DavTarget.Collection -> p.id
        else -> throw ApiException(DAV_MISSING_PARENT, HttpStatusCode.Conflict, "父路径不存在")
    }
    if (resolveDavPath(userId, segments) !is DavTarget.Missing) {
        throw ApiException(DAV_METHOD_NOT_ALLOWED, HttpStatusCode.MethodNotAllowed, "资源已存在")
    }
    // Name validation and the sibling-clash check stay in the service layer:
    // one rule about what may be called what, not two copies of it.
    withContext(Dispatchers.IO) { FileService.createFolder(userId, parent?.toString(), name) }
    respond(HttpStatusCode.Created)
}

// ---- MOVE / COPY -------------------------------------------------------------

/**
 * MOVE and COPY, which differ only in what they do to the source row.
 *
 * The status pair is RFC 4918 §9.9.4's: 201 when the destination was created,
 * 204 when an existing one was replaced or left alone. `Overwrite: F` against an
 * occupied destination is answered 204 rather than the RFC's 412, because a
 * mount client that copies a folder over one it already has is doing something
 * completely ordinary, and 412 reads to it as "your validator is stale, reload
 * and retry" - the wrong instruction for "you already have this".
 *
 * Everything is decided before a row is written: the conditional headers, the
 * destination shape, and the self-nesting checks all run before
 * [davClearDestination] destroys what is in the way.
 */
internal suspend fun ApplicationCall.respondDavMoveCopy(copying: Boolean) {
    // The flag is named after the action, not after the method. A first draft
    // took a `move: Boolean` and passed `move = false` from the MOVE route,
    // which reads correctly and silently ran the copy path - COPY left the
    // source in place and MOVE duplicated rows instead of renaming one.
    davRequireQuota()
    val userId = davWritable().userId
    val source = davSegments(request.path().removePrefix("/dav"))
        ?: throw ApiException.notFound("资源不存在")
    val destination = davDestinationSegments()
    val overwrite = davOverwrite()
    // The account root has no row to move and no name to give the result.
    if (source.isEmpty() || destination.isEmpty()) {
        throw ApiException(DAV_METHOD_NOT_ALLOWED, HttpStatusCode.MethodNotAllowed, "根集合不能作为 MOVE/COPY 的目标")
    }
    // Both paths are resolved before anything else so a missing source is a 404
    // rather than a 409 about the destination the client may not be able to name.
    val from = when (val resolved = resolveDavPath(userId, source)) {
        DavTarget.Missing -> throw ApiException.notFound("资源不存在")
        else -> resolved
    }
    val destParent = when (val p = resolveDavPath(userId, destination.dropLast(1))) {
        is DavTarget.Collection -> p.id
        else -> throw ApiException(DAV_MISSING_PARENT, HttpStatusCode.Conflict, "目标父路径不存在")
    }
    val occupied = resolveDavPath(userId, destination)
    val name = destination.last()

    // RFC 4918 §10.4: the conditional headers of a state-changing method are
    // evaluated against the source before anything is touched. COPY has no
    // condition on the source - RFC puts `If` on the *destination*, whose state
    // only the overwrite branch can change - so it is skipped there.
    if (!copying) davPreconditionOn(userId, from)

    if (occupied !is DavTarget.Missing && !overwrite) {
        respond(HttpStatusCode.NoContent)
        return
    }
    // A file is not a collection, so replacing one with the other is undefined
    // rather than a merge: 405 says "change the request", 204 would have
    // destroyed the collection's subtree.
    if (from is DavTarget.Blob && occupied is DavTarget.Collection) {
        throw ApiException(DAV_METHOD_NOT_ALLOWED, HttpStatusCode.MethodNotAllowed, "集合不能作为文件的目标")
    }
    if (davRefuseSelfNesting(userId, from, destParent, occupied, copying)) {
        respond(HttpStatusCode.NoContent)
        return
    }

    val replaced = occupied !is DavTarget.Missing
    val storage = davWriteStorage()
    val (keys, sessions) = withContext(Dispatchers.IO) { davClearDestination(userId, occupied) }
    withContext(Dispatchers.IO) {
        if (copying) {
            davCopyFrom(userId, from, destParent, name)
        } else {
            FileService.moveTo(userId, davTargetId(from), destParent, name)
        }
    }
    // Blob deletion and staging cleanup happen AFTER the transaction committed:
    // "this key lost its last reference" is only true then.
    deleteStoredBlobs(storage, keys)
    sessions.forEach { storage.tmpDir.resolve(it.toString()).toFile().deleteRecursively() }
    respond(if (replaced) HttpStatusCode.NoContent else HttpStatusCode.Created)
}

/**
 * The row a MOVE or COPY reads, and the only way a source reaches the service
 * layer - so "resolved above" cannot go stale between the checks and the write.
 */
private fun davTargetId(target: DavTarget): UUID = when (target) {
    is DavTarget.Collection -> requireNotNull(target.id) { "the account root is not a movable row" }
    is DavTarget.Blob -> target.id
    DavTarget.Missing -> throw ApiException.notFound("资源不存在")
}

private fun davCopyFrom(userId: UUID, from: DavTarget, destParent: UUID?, name: String) {
    when (from) {
        is DavTarget.Blob -> FileService.copyFile(userId, from.id, destParent, name)
        is DavTarget.Collection -> FileService.copySubtree(userId, requireNotNull(from.id), destParent, name)
        DavTarget.Missing -> throw ApiException.notFound("资源不存在")
    }
}

/**
 * Refuse the destination shapes that cannot be carried out, and report the one
 * that is a legal no-op.
 *
 * Four cases, all of which are 403 rather than 409 or 500 because the client
 * asked for something that is not a filesystem question at all:
 *
 *  - the destination parent is inside the source collection (which is what makes
 *    a copy loop forever and a move a cycle the self-referencing FK accepts);
 *  - the occupied destination *is* the source - a MOVE onto its own URL is a
 *    no-op, and a COPY onto itself would have to delete the bytes it is about to
 *    read (RFC 4918 §9.8.3 says 403);
 *  - the occupied destination is an ancestor of the source, which is the same
 *    hazard one level up: replacing it would take the source with it;
 *  - and the mirror case for a plain file, where only "the same file at the same
 *    place" is a special case.
 *
 * Returns true when the request is that no-op and the caller should answer 204.
 */
private suspend fun ApplicationCall.davRefuseSelfNesting(
    userId: UUID,
    from: DavTarget,
    destParent: UUID?,
    occupied: DavTarget,
    copying: Boolean,
): Boolean {
    val sourceFolder = (from as? DavTarget.Collection)?.id
    val occupiedFolder = (occupied as? DavTarget.Collection)?.id
    if (sourceFolder != null) {
        if (destParent != null &&
            withContext(Dispatchers.IO) { FileService.isInSubtree(userId, sourceFolder, destParent) }
        ) {
            throw ApiException(DAV_INTO_SELF, HttpStatusCode.Forbidden, "目标路径位于源集合之内")
        }
        if (occupiedFolder != null &&
            withContext(Dispatchers.IO) { FileService.isInSubtree(userId, occupiedFolder, sourceFolder) }
        ) {
            if (!copying && occupiedFolder == sourceFolder) return true
            throw ApiException(DAV_INTO_SELF, HttpStatusCode.Forbidden, "目标集合包含源路径")
        }
        return false
    }
    if (!copying && occupied is DavTarget.Blob && from is DavTarget.Blob && occupied.id == from.id) return true
    return false
}

/**
 * Clear whatever occupies the destination, so the move or copy can land there.
 * Returns the blob keys that lost their last reference and the upload sessions
 * whose staging directories have to go - both for the caller to act on *after*
 * the transaction committed.
 *
 * A file is trashed rather than removed, for two reasons: it is the same promise
 * the app's own delete makes (a mount overwrite is undoable from the trash), and
 * the surviving row keeps its blob referenced, so replacing a name queues no
 * bytes. A collection cannot be trashed at all - `folders` has no deleted_at -
 * and a soft-deleted file still carries its `folder_id`, so its rows cannot go
 * while the folder row lives. It is therefore the app's own folder delete,
 * which is also what the app's folder delete does.
 */
private fun davClearDestination(userId: UUID, occupied: DavTarget): Pair<List<String>, List<UUID>> = when (occupied) {
    DavTarget.Missing -> emptyList<String>() to emptyList()
    is DavTarget.Blob -> {
        FileService.trashFile(userId, occupied.id)
        emptyList<String>() to emptyList()
    }
    is DavTarget.Collection -> {
        val id = occupied.id ?: return emptyList<String>() to emptyList()
        val deletion = FileService.deleteFolder(userId, id)
        deletion.blobKeys to deletion.removedSessionIds
    }
}

/**
 * The `Destination` header as path segments of this server's own /dav surface.
 *
 * RFC 4918 §10.3 allows an absolute URI or an absolute path, and a mount sends
 * the first - the URL it was handed, echoed back. Both spellings are accepted and
 * both are reduced to a path.
 *
 * The authority is checked rather than ignored. The server never redirects
 * anywhere and never fetches the header, so accepting a foreign host would not
 * be an open-redirect bug here; it would be a protocol violation that tells a
 * client a move happened when this server was never part of it - and, for a
 * client that resolves the header itself, one that hands the mount credential to
 * whoever the header named. A Destination whose host is not ours is therefore a
 * 400 like any other malformed header. The comparison is against the request's
 * own `Host`, which is what a reverse proxy in front of this instance has to
 * preserve anyway for the URLs it advertises to be mountable.
 */
private fun ApplicationCall.davDestinationSegments(): List<String> {
    val raw = request.headers[HttpHeaders.Destination]?.trim()
    if (raw.isNullOrEmpty()) {
        throw ApiException.badRequest("MOVE/COPY 需要 Destination 头", DAV_BAD_DESTINATION)
    }
    val uri = runCatching { URI(raw) }.getOrNull()
        ?: throw ApiException.badRequest("Destination 头无效", DAV_BAD_DESTINATION)
    if (uri.scheme != null || uri.rawAuthority != null) {
        val host = uri.host
        if (host.isNullOrBlank() || !host.equals(request.host(), ignoreCase = true)) {
            throw ApiException.badRequest("Destination 必须指向本服务器", DAV_BAD_DESTINATION)
        }
    }
    // Everything on this surface lives under /dav. A Destination elsewhere would
    // otherwise resolve to a *different* resource - the account root of the API
    // namespace - which is the kind of accident that copies a file over the
    // wrong thing once and is very hard to see afterwards.
    val path = uri.rawPath.orEmpty()
    if (!path.startsWith("/dav")) {
        throw ApiException.badRequest("Destination 必须位于 /dav 之下", DAV_BAD_DESTINATION)
    }
    return davSegments(path.removePrefix("/dav"))
        ?: throw ApiException.badRequest("Destination 路径非法", DAV_BAD_DESTINATION)
}

/**
 * RFC 4918 §10.3's `Overwrite`, defaulting to T.
 *
 * An unrecognised value is 400 rather than a default: a client that sent
 * something else has a bug, and silently reading it as "yes, destroy what is
 * there" is the one reading of an unknown header that loses data.
 */
private fun ApplicationCall.davOverwrite(): Boolean =
    when (request.headers[HttpHeaders.Overwrite]?.trim()?.uppercase()) {
        null, "", "T" -> true
        "F" -> false
        else -> throw ApiException.badRequest("Overwrite 头非法", DAV_BAD_OVERWRITE)
    }

// ---- DELETE ------------------------------------------------------------------

/**
 * DELETE, answered 204.
 *
 * A file is trashed: a mount client deleting a file should not be able to make
 * it unrecoverable, and the app has a trash for exactly that. A collection is
 * the app's folder delete, which removes its rows - see [davClearDestination]
 * for why there is no folder trash to fall back on.
 *
 * A DELETE of the account root is 405. It has no row, and "empty the drive" is
 * not something this verb should be able to spell by accident.
 */
internal suspend fun ApplicationCall.respondDavDelete() {
    davRequireQuota()
    val userId = davWritable().userId
    val segments = davSegments(request.path().removePrefix("/dav"))
        ?: throw ApiException.notFound("资源不存在")
    if (segments.isEmpty()) {
        throw ApiException(DAV_METHOD_NOT_ALLOWED, HttpStatusCode.MethodNotAllowed, "不能删除根集合")
    }
    val target = when (val t = resolveDavPath(userId, segments)) {
        DavTarget.Missing -> throw ApiException.notFound("资源不存在")
        else -> t
    }
    davPreconditionOn(userId, target)
    val storage = davWriteStorage()
    val (keys, sessions) = withContext(Dispatchers.IO) { davClearDestination(userId, target) }
    deleteStoredBlobs(storage, keys)
    sessions.forEach { storage.tmpDir.resolve(it.toString()).toFile().deleteRecursively() }
    respond(HttpStatusCode.NoContent)
}

// ---- shared ------------------------------------------------------------------

/**
 * The conditional headers of a state-changing method, refused before anything is
 * touched. Both non-`Proceed` verdicts are 412 here and not the 304 a GET would
 * get: RFC 9110 answers `If-None-Match: *` against an existing resource with 412
 * for a write, and a 304 on a DELETE tells a client the *request* was not
 * modified.
 */
private suspend fun ApplicationCall.davPreconditionOn(userId: UUID, target: DavTarget) {
    val (etag, modified) = withContext(Dispatchers.IO) { davValidators(userId, target) }
    if (davPrecondition(etag, modified) != DavPrecondition.Proceed) {
        throw ApiException(
            DAV_PRECONDITION_FAILED, HttpStatusCode.PreconditionFailed,
            "资源的 ETag 已变化，请重新读取后再试",
        )
    }
}

/**
 * The two values a conditional request compares: a file's content digest (a
 * strong validator for free, since the storage is content-addressed) or a
 * collection's update timestamp, because a collection has no content to tag.
 *
 * Read live-only for the same reason PUT's validator probe is: a row trashed
 * between the path walk and here is not a resource the client may act on.
 */
private fun davValidators(userId: UUID, target: DavTarget): Pair<String?, Long?> = when (target) {
    DavTarget.Missing -> null to null
    is DavTarget.Blob -> transaction(DatabaseFactory.db) {
        FilesTable.select(FilesTable.sha256, FilesTable.updatedAt)
            .where { (FilesTable.id eq target.id) and (FilesTable.user eq userId) and (FilesTable.deletedAt eq 0L) }
            .firstOrNull()
            ?.let { "\"${it[FilesTable.sha256]}\"" to it[FilesTable.updatedAt] }
    } ?: (null to null)
    is DavTarget.Collection -> target.id?.let { folderId ->
        transaction(DatabaseFactory.db) {
            FoldersTable.select(FoldersTable.updatedAt)
                .where { (FoldersTable.id eq folderId) and (FoldersTable.user eq userId) }
                .firstOrNull()
                ?.let { null to it[FoldersTable.updatedAt] }
        }
    } ?: (null to null)
}

/**
 * The provider this mount writes through.
 *
 * Resolved from the registry for the same reason PUT resolves it there: every
 * bare key in `files.storage_key` means the backend this process started with.
 * Declared separately rather than shared with PUT because that helper is private
 * to DavPut.kt - and a second opinion about which backend is live is exactly the
 * kind of divergence a "one helper" note is supposed to prevent.
 */
private fun davWriteStorage(): StorageProvider =
    StorageRegistry.defaultProvider() ?: error("no storage backend registered")