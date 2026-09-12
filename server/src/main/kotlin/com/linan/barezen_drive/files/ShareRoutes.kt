package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.ShareCreateRequest
import com.linan.barezen_drive.core.dto.SharesResponse
import com.linan.barezen_drive.storage.StorageProvider
import com.linan.barezen_drive.storage.thumbKey
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Token characters are hex only; anything else is a guaranteed miss, no DB lookup.
private val TOKEN_RE = Regex("^[0-9a-f]{64}$")

/**
 * Share links: owner management (JWT) plus unauthenticated public endpoints.
 * Mount ownerRoutes inside authenticate("auth-jwt"); publicRoutes stays at the
 * routing top level because share visitors have no account. Reserved /api
 * prefix keeps static hosting away from both.
 */
fun Route.shareOwnerRoutes() {
    route("/api/shares") {
        post {
            val req = call.receive<ShareCreateRequest>()
            val dto = withContext(Dispatchers.IO) { ShareService.createShare(call.userId, req) }
            call.respond(HttpStatusCode.Created, dto)
        }
        get {
            val fileId = call.request.queryParameters["fileId"]
            val folderId = call.request.queryParameters["folderId"]
            val shares = withContext(Dispatchers.IO) { ShareService.listShares(call.userId, fileId, folderId) }
            call.respond(SharesResponse(shares))
        }
        delete("/{id}") {
            val id = call.parameters["id"]!!.toUuidOrBadRequest()
            withContext(Dispatchers.IO) { ShareService.revokeShare(call.userId, id) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

fun Route.sharePublicRoutes(storage: StorageProvider) {
    route("/api/public/shares/{token}") {
        get {
            val share = resolve(call)
            val info = withContext(Dispatchers.IO) { ShareService.sharedInfo(share) }
            withContext(Dispatchers.IO) { ShareService.recordView(share) }
            call.respond(info)
        }

        get("/contents") {
            val share = resolve(call)
            val info = withContext(Dispatchers.IO) { ShareService.sharedInfo(share) }
            if (info.type != "folder") throw com.linan.barezen_drive.api.ApiException.notFound("不是文件夹分享")
            val folder = call.request.queryParameters["folder"]
            val contents = withContext(Dispatchers.IO) { ShareService.sharedContents(share, folder) }
            withContext(Dispatchers.IO) { ShareService.recordView(share) }
            call.respond(contents)
        }

        get("/files/{fid}/content") {
            val share = resolve(call)
            val fid = call.parameters["fid"]!!.toUuidOrBadRequest()
            val meta = withContext(Dispatchers.IO) { ShareService.sharedFileMeta(share, fid) }
            withContext(Dispatchers.IO) { ShareService.recordDownload(share) }
            val contentType = meta.mimeType?.let { mime ->
                runCatching { ContentType.parse(mime) }.getOrNull()
            } ?: ContentType.Application.OctetStream
            // PartialContent plugin handles Range/206/416 on this streamed body.
            call.respond(FileStream(withContext(Dispatchers.IO) { storage.get(meta.storageKey) }, meta.size, contentType))
        }

        get("/files/{fid}/thumbnail") {
            val share = resolve(call)
            val fid = call.parameters["fid"]!!.toUuidOrBadRequest()
            val meta = withContext(Dispatchers.IO) { ShareService.sharedFileMeta(share, fid) }
            if (!meta.hasThumbnail) throw com.linan.barezen_drive.api.ApiException.notFound("文件没有缩略图")
            val bytes = withContext(Dispatchers.IO) { storage.get(thumbKey(meta.sha256)).toInputStream().readBytes() }
            call.response.header(HttpHeaders.ETag, meta.sha256)
            call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
            call.respondBytes(bytes, ContentType.Image.JPEG)
        }
    }
}

/** Shared guard for every public endpoint: hex-format check first, then lookup. */
private fun resolve(call: ApplicationCall): ResolvedShare {
    val token = call.parameters["token"] ?: throw com.linan.barezen_drive.api.ApiException.notFound("链接无效或已过期")
    if (!TOKEN_RE.matches(token)) throw com.linan.barezen_drive.api.ApiException.notFound("链接无效或已过期")
    return ShareService.resolveToken(token)
}
