package com.linan.barezen_drive.system

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.AdminUserDto
import com.linan.barezen_drive.core.dto.AdminUsersResponse
import com.linan.barezen_drive.core.dto.ErrorCodes
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.RefreshTokensTable
import com.linan.barezen_drive.db.ShareLinksTable
import com.linan.barezen_drive.db.UploadChunksTable
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.files.FileService
import com.linan.barezen_drive.storage.StorageProvider
import com.linan.barezen_drive.storage.thumbKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * Owner-facing user management for a self-hosted drive. The server's model is
 * single-owner: any signed-in account administers the instance, matching the
 * registration switch.
 */
fun Route.adminUserRoutes(storage: StorageProvider) {
    authenticate("auth-jwt") {
        get("/api/admin/users") {
            call.userId
            val users = withContext(Dispatchers.IO) {
                transaction(DatabaseFactory.db) {
                    UsersTable.selectAll()
                        .orderBy(UsersTable.createdAt, SortOrder.ASC)
                        .map { row ->
                            val uid = row[UsersTable.id]
                            AdminUserDto(
                                id = uid.toString(),
                                username = row[UsersTable.username],
                                createdAt = Instant.ofEpochMilli(row[UsersTable.createdAt]).toString(),
                                fileCount = FilesTable.selectAll().where { FilesTable.user eq uid }.count(),
                            )
                        }
                }
            }
            call.respond(AdminUsersResponse(users))
        }

        delete("/api/admin/users/{id}") {
            call.userId
            val target = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: throw ApiException.badRequest("用户 ID 非法", ErrorCodes.VALIDATION_ERROR)
            val blobKeys = withContext(Dispatchers.IO) {
                transaction(DatabaseFactory.db) {
                    UsersTable.selectAll().where { UsersTable.id eq target }.singleOrNull()
                        ?: throw ApiException.notFound("用户不存在")
                    // Reuse the tested recursive folder deletion for every root:
                    // it cleans upload sessions/chunks, files and folders
                    // (children first), and reports blobs whose refcount hits zero.
                    val keys = mutableListOf<String>()
                    val sessionIds = mutableListOf<UUID>()
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
                    FilesTable.selectAll().where { FilesTable.user eq target }
                        .forEach { keys.add(it[FilesTable.storageKey]) }
                    FilesTable.deleteWhere { user eq target }
                    FoldersTable.deleteWhere { user eq target }
                    ShareLinksTable.deleteWhere { user eq target }
                    RefreshTokensTable.deleteWhere { RefreshTokensTable.user eq target }
                    UsersTable.deleteWhere { UsersTable.id eq target }
                    keys.distinct().filter { key ->
                        FilesTable.selectAll().where { FilesTable.storageKey eq key }.count() == 0L
                    }
                }
            }
            // Mirror FolderRoutes cleanup: blobs, their thumbnails, temp parts.
            blobKeys.forEach { key ->
                runCatching {
                    storage.delete(key)
                    storage.delete(thumbKey(key.substringAfterLast('/')))
                }
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
