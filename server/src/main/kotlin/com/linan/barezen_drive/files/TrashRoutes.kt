package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.TrashResponse
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Trash endpoints (soft-deleted files). Registered inside the strict
 * authenticate block: the trash is always scoped to the caller's own rows.
 *
 * Deleting here is permanent, and the plain file delete route is what puts a
 * row in here in the first place. Rows older than the retention window are
 * purged by the cleanup loop, so this screen is never the only way out.
 */
fun Route.trashRoutes(storage: StorageProvider) {
    route("/api/trash") {
        get {
            val files = withContext(Dispatchers.IO) { FileService.listTrash(call.userId) }
            call.respond(TrashResponse(files))
        }
        post("/{id}/restore") {
            val id = call.parameters["id"]!!.toUuidOrBadRequest()
            val file = withContext(Dispatchers.IO) { FileService.restoreFile(call.userId, id) }
            call.respond(file)
        }
        delete("/{id}") {
            val id = call.parameters["id"]!!.toUuidOrBadRequest()
            val keys = withContext(Dispatchers.IO) { FileService.deleteForever(call.userId, id) }
            deleteStoredBlobs(storage, keys)
            call.respond(HttpStatusCode.NoContent)
        }
        delete {
            val keys = withContext(Dispatchers.IO) { FileService.emptyTrash(call.userId) }
            deleteStoredBlobs(storage, keys)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
