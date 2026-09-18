package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.ErrorCodes
import com.linan.barezen_drive.core.dto.UploadInitRequest
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.uploadRoutes(storage: StorageProvider) {
    route("/api/uploads") {
        post("/init") {
            val req = call.receive<UploadInitRequest>()
            // initUpload opens several transactions; keep them off the
            // event-loop thread (see also putChunk/abort below).
            val res = withContext(Dispatchers.IO) { UploadService.initUpload(call.userId, req, storage) }
            call.respond(res)
        }
        put("/{id}/chunks/{index}") {
            val sessionId = call.parameters["id"]!!.toUuidOrBadRequest()
            val index = call.parameters["index"]!!.toIntOrNull()
                ?: throw ApiException.badRequest("chunk index 非法", ErrorCodes.CHUNK_INVALID)
            // Streamed: the chunk body never buffers in memory (a 20 MiB body per
            // concurrent upload used to multiply the JVM heap pressure on 1G hosts).
            val chunkSha = call.request.headers["X-Chunk-Sha256"]
            val body = call.request.receiveChannel()
            withContext(Dispatchers.IO) {
                UploadService.putChunk(call.userId, sessionId, index, body, chunkSha, storage)
            }
            call.respond(HttpStatusCode.NoContent)
        }
        post("/{id}/complete") {
            val sessionId = call.parameters["id"]!!.toUuidOrBadRequest()
            call.respond(UploadService.complete(call.userId, sessionId, storage))
        }
        delete("/{id}") {
            val sessionId = call.parameters["id"]!!.toUuidOrBadRequest()
            withContext(Dispatchers.IO) { UploadService.abort(call.userId, sessionId, storage) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
