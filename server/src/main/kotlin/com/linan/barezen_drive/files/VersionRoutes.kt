package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.FileVersionsResponse
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Version history endpoints for a single file. Mounted next to the other
 * /api/files routes inside the authenticated block (see Application.module).
 */
fun Route.versionRoutes(storage: StorageProvider) {
    route("/api/files/{id}/versions") {
        get {
            val id = call.parameters["id"]!!.toUuidOrBadRequest()
            val versions = withContext(Dispatchers.IO) { VersionService.list(call.userId, id) }
            call.respond(FileVersionsResponse(versions))
        }
        post("/{versionId}/restore") {
            val id = call.parameters["id"]!!.toUuidOrBadRequest()
            val versionId = call.parameters["versionId"]?.toUuidOrBadRequest()
                ?: throw ApiException.badRequest("versionId 非法")
            call.respond(VersionService.restore(call.userId, id, versionId, storage))
        }
        delete("/{versionId}") {
            val id = call.parameters["id"]!!.toUuidOrBadRequest()
            val versionId = call.parameters["versionId"]?.toUuidOrBadRequest()
                ?: throw ApiException.badRequest("versionId 非法")
            VersionService.deleteVersion(call.userId, id, versionId, storage)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
