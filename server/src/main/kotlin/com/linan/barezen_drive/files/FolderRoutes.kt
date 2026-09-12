package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.*
import com.linan.barezen_drive.storage.StorageProvider
import com.linan.barezen_drive.storage.thumbKey
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.folderRoutes(storage: StorageProvider) {
    route("/api/folders") {
        get("/{id}/contents") {
            call.respond(withContext(Dispatchers.IO) { FileService.contents(call.userId, call.parameters["id"]!!) })
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
            deletion.blobKeys.forEach { storage.delete(it) }
            // Covers are content-addressed by the same sha256; once no row references
            // the content anymore its cover is unreachable too.
            deletion.blobKeys.map { it.substringAfterLast('/') }.forEach { storage.delete(thumbKey(it)) }
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
            val keys = withContext(Dispatchers.IO) { FileService.deleteFile(call.userId, id) }
            keys.forEach { storage.delete(it) }
            keys.map { it.substringAfterLast('/') }.forEach { storage.delete(thumbKey(it)) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
