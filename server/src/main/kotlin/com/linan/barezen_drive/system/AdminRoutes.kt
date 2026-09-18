package com.linan.barezen_drive.system

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.AdminUserDto
import com.linan.barezen_drive.core.dto.AdminUsersResponse
import com.linan.barezen_drive.core.dto.ErrorCodes
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FileVersionsTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.RefreshTokensTable
import com.linan.barezen_drive.db.ShareLinksTable
import com.linan.barezen_drive.db.UploadChunksTable
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.files.FileService
import com.linan.barezen_drive.files.deleteStoredBlobs
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * Owner-only user management for a self-hosted drive. The server's model is
 * single-owner: the first account ever created administers the instance (see
 * OwnerGuard). Registration may be open, so "signed in" is not enough - a
 * guest account must never enumerate users or delete the owner's data.
 */
fun Route.adminUserRoutes(storage: StorageProvider) {
    authenticate("auth-jwt") {
        get("/api/admin/users") {
            requireOwner(call.userId)
            val users = withContext(Dispatchers.IO) {
                transaction(DatabaseFactory.db) {
                    // One GROUP BY instead of one COUNT query per user row: the
                    // list endpoint stays O(1) round trips regardless of how
                    // many accounts exist. Raw SQL because Exposed 0.61 does
                    // not resolve ColumnSet.slice for this shape on Table
                    // receivers (only the array-slice extension matches).
                    val fileCounts = HashMap<UUID, Long>()
                    exec("SELECT user_id, COUNT(*) AS cnt FROM files GROUP BY user_id") { rs ->
                        while (rs.next()) {
                            fileCounts[UUID.fromString(rs.getString("user_id"))] = rs.getLong("cnt")
                        }
                        null
                    }
                    UsersTable.selectAll()
                        .orderBy(UsersTable.createdAt, SortOrder.ASC)
                        .map { row ->
                            val uid = row[UsersTable.id]
                            AdminUserDto(
                                id = uid.toString(),
                                username = row[UsersTable.username],
                                createdAt = Instant.ofEpochMilli(row[UsersTable.createdAt]).toString(),
                                fileCount = fileCounts[uid] ?: 0L,
                            )
                        }
                }
            }
            call.respond(AdminUsersResponse(users))
        }

        delete("/api/admin/users/{id}") {
            requireOwner(call.userId)
            val target = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw ApiException.badRequest("用户 ID 非法", ErrorCodes.VALIDATION_ERROR)
            // The owner account carries the instance's administration; deleting
            // it would hand the instance to whichever account registered next.
            if (target == ownerId()) throw ApiException.badRequest("所有者账号不可删除")
            val (blobKeys, sessionIds) = withContext(Dispatchers.IO) {
                val sessionIds = mutableListOf<UUID>()
                val blobs = transaction(DatabaseFactory.db) {
                    UsersTable.selectAll().where { UsersTable.id eq target }.singleOrNull()
                        ?: throw ApiException.notFound("用户不存在")
                    // Reuse the tested recursive folder deletion for every root:
                    // it cleans upload sessions/chunks, files and folders
                    // (children first), and reports blobs whose refcount hits zero.
                    val keys = mutableListOf<String>()
                    val roots = FoldersTable.selectAll()
                        .where { (FoldersTable.user eq target) and FoldersTable.parent.isNull() }
                        .map { it[FoldersTable.id] }
                    for (root in roots) {
                        val deletion = FileService.deleteFolder(target, root)
                        keys += deletion.blobKeys
                        sessionIds += deletion.removedSessionIds
                    }
                    // Sessions and files outside any folder subtree.
                    UploadSessionsTable.selectAll()
                        .where { UploadSessionsTable.user eq target }
                        .forEach { r -> sessionIds.add(r[UploadSessionsTable.id]) }
                    for (sid in sessionIds) {
                        UploadChunksTable.deleteWhere { UploadChunksTable.session eq sid }
                        UploadSessionsTable.deleteWhere { UploadSessionsTable.id eq sid }
                    }
                    // Files outside any folder subtree (root-level uploads): their
                    // version snapshots must be collected BEFORE the file rows go,
                    // mirroring FileService.hardDelete - the FK cascade would remove
                    // the rows silently and leak their blobs, and the refcount sweep
                    // below would never see those keys.
                    val doomed = FilesTable.selectAll().where { FilesTable.user eq target }
                        .map { it[FilesTable.id] to it[FilesTable.storageKey] }
                    if (doomed.isNotEmpty()) {
                        val doomedIds = doomed.map { it.first }
                        val versionOp = SqlExpressionBuilder.run { FileVersionsTable.file inList doomedIds }
                        FileVersionsTable.selectAll().where { versionOp }.forEach { keys.add(it[FileVersionsTable.storageKey]) }
                        FileVersionsTable.deleteWhere { versionOp }
                    }
                    keys += doomed.map { it.second }
                    FilesTable.deleteWhere { user eq target }
                    FoldersTable.deleteWhere { user eq target }
                    ShareLinksTable.deleteWhere { user eq target }
                    RefreshTokensTable.deleteWhere { RefreshTokensTable.user eq target }
                    UsersTable.deleteWhere { UsersTable.id eq target }
                    // Shared refcount sweep: a key survives when ANY user's live row
                    // or version snapshot still references it (content-addressed
                    // dedup makes cross-user sharing routine). Checking only this
                    // user's rows would delete blobs other accounts' history uses.
                    FileService.orphanBlobKeys(keys)
                }
                blobs to sessionIds
            }
            // Same choke point as every other purge path: re-counts references in
            // a fresh transaction right before the unlink, then removes the blob
            // and its content-addressed thumbnail.
            deleteStoredBlobs(storage, blobKeys)
            sessionIds.forEach { sid ->
                runCatching { storage.tmpDir.resolve(sid.toString()).toFile().deleteRecursively() }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
