package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.*
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.folderRoutes(storage: StorageProvider) {
    route("/api/folders") {
        get("/{id}/contents") {
            // No limit -> the whole folder (unchanged contract for the callers
            // that only read `folders`). A limit is clamped so a hostile or
            // fat-fingered value cannot ask for a million rows.
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1000)
            val cursor = call.request.queryParameters["cursor"]
            call.respond(
                withContext(Dispatchers.IO) {
                    FileService.contents(call.userId, call.parameters["id"]!!, limit, cursor)
                },
            )
        }
        post {
            val req = call.receive<CreateFolderRequest>()
            call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { FileService.createFolder(call.userId, req.parentId, req.name) })
        }
        patch("/{id}") {
            val req = call.receive<RenameFolderRequest>()
            val id = call.parameters["id"]!!.toUuidOrBadRequest()
            call.respond(withContext(Dispatchers.IO) { FileService.renameFolder(call.userId, id, req.name) })
        }
        delete("/{id}") {
            val id = call.parameters["id"]!!.toUuidOrBadRequest()
            val deletion = withContext(Dispatchers.IO) { FileService.deleteFolder(call.userId, id) }
            // Physical blob deletion and aborted session tmp cleanup happen AFTER the
            // transaction commits (refcount zero / rows already marked aborted).
            deleteStoredBlobs(storage, deletion.blobKeys)
            deletion.removedSessionIds.forEach { sid ->
                storage.tmpDir.resolve(sid.toString()).toFile().deleteRecursively()
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
    route("/api/files") {
        patch("/{id}") {
            val req = call.receive<UpdateFileRequest>()
            val id = call.parameters["id"]!!.toUuidOrBadRequest()
            call.respond(withContext(Dispatchers.IO) { FileService.updateFile(call.userId, id, req.name, req.folderId) })
        }
        delete("/{id}") {
            val id = call.parameters["id"]!!.toUuidOrBadRequest()
            // Soft delete: the row moves to the trash and keeps its blob. Only
            // the trash endpoints (or the retention sweep) remove bytes.
            withContext(Dispatchers.IO) { FileService.trashFile(call.userId, id) }
            call.respond(HttpStatusCode.NoContent)
        }
        put("/{id}/favorite") {
            val req = call.receive<FavoriteRequest>()
            val id = call.parameters["id"]!!.toUuidOrBadRequest()
            call.respond(withContext(Dispatchers.IO) { FileService.setFavorite(call.userId, id, req.favorite) })
        }
        put("/{id}/archive") {
            val req = call.receive<ArchiveRequest>()
            val id = call.parameters["id"]!!.toUuidOrBadRequest()
            call.respond(withContext(Dispatchers.IO) { FileService.setArchived(call.userId, id, req.archived) })
        }
    }
}
