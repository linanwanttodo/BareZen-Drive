package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.Throttle
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.api.clientIp
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
 * Requests per minute one visitor may spend on the public share endpoints
 * (landing page, folder listing, thumbnail) for one link.
 *
 * 300/min is 5 requests per second. A visitor clicking through a folder share
 * spends one listing plus a handful of lazily-composed thumbnails per screen -
 * the client only fetches covers for rows the list actually shows - so real
 * browsing stays an order of magnitude below this, while a script walking the
 * endpoint, or one caller guessing token after token, runs out long before it
 * can drain the five-connection pool.
 *
 * Keyed per (client address, share token), not per address: a household or an
 * office shares one address, and an address-only window would let one person's
 * browsing lock everyone else out of the link. The token is what the caller
 * actually presents, so it belongs in the key - and because every guess is a
 * different token, the address is what keeps the guessing case bounded, which
 * is why the number is generous rather than tight.
 */
internal const val SHARE_PUBLIC_META_BUDGET = 300

/**
 * Requests per minute on the body route, which is twice the metadata budget.
 *
 * This is the one public endpoint with a legitimate burst: a media player
 * issues a Range request every time it needs more buffer, so a 4K stream on a
 * slow link is a steady trickle of small ranges while it plays. 600/min leaves
 * roughly 3x headroom over that, which is enough for a 16-connection download
 * manager, while still stopping the "pull the same multi-gigabyte file in a
 * loop" amplification. Seek and suffix ranges are counted against it like any
 * other: bounding the number of requests is what bounds the bytes, since every
 * one of them costs a token lookup and a connection either way.
 */
internal const val SHARE_PUBLIC_BODY_BUDGET = 600

private const val SHARE_PUBLIC_WINDOW_MS = 60_000L

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
            val token = guard(call, body = false)
            val opened = withContext(Dispatchers.IO) { ShareService.openInfo(token, call.clientIp()) }
            call.respond(opened.value)
        }

        get("/contents") {
            val token = guard(call, body = false)
            val folder = call.request.queryParameters["folder"]
            val opened = withContext(Dispatchers.IO) {
                ShareService.openContents(token, folder, call.clientIp())
            }
            // The share has to be a folder share. Checked on the resolved
            // target rather than on a separate metadata read, so a file share
            // costs one transaction like every other request here.
            if (opened.share.folderId == null) {
                throw ApiException.notFound("不是文件夹分享")
            }
            call.respond(opened.value)
        }

        get("/files/{fid}/content") {
            val token = guard(call, body = true)
            val fid = call.parameters["fid"]!!.toUuidOrBadRequest()
            // Count one download per transfer, not per HTTP request: media
            // players fire many Range requests during one playback, and the
            // PartialContent plugin answers each separately. Only a body that
            // starts at byte 0 (no Range, or "bytes=0-...") counts; seek and
            // suffix requests do not.
            val range = call.request.headers[HttpHeaders.Range]
            val opensAtZero = range == null || range.startsWith("bytes=0-")
            val opened = withContext(Dispatchers.IO) {
                ShareService.openFile(token, fid, countDownload = opensAtZero)
            }
            // Same responder as /api/files/{id}/content: the content-type
            // policy (see SafeContent), the 404 for a share that outlived its
            // blob and the open-once-then-close streaming are one
            // implementation, not two that can drift. This route needs no
            // account, only the link, so it is the more exposed of the two.
            call.respondStoredFile(storage, opened.value)
        }

        get("/files/{fid}/thumbnail") {
            val token = guard(call, body = false)
            val fid = call.parameters["fid"]!!.toUuidOrBadRequest()
            val opened = withContext(Dispatchers.IO) {
                ShareService.openFile(token, fid, countDownload = false)
            }
            val meta = opened.value
            if (!meta.hasThumbnail) throw ApiException.notFound("文件没有缩略图")
            val key = thumbKey(meta.sha256)
            val bytes = withContext(Dispatchers.IO) {
                if (!storage.exists(key)) throw ApiException.notFound("缩略图不存在")
                // use {}: readBytes() to the end does not close the channel's
                // stream, and a thumbnail fetch happens once per visible row.
                storage.get(key).toInputStream().use { it.readBytes() }
            }
            call.response.header(HttpHeaders.ETag, meta.sha256)
            call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
            call.respondBytes(bytes, ContentType.Image.JPEG)
        }
    }
}

/**
 * Entry guard for every public endpoint: hex-format check, then the rate
 * limiter, then - in the caller's Dispatchers.IO block - the database.
 *
 * The format check comes first because it costs nothing: a token that could
 * never match a row must not spend a database query, and must not spend a
 * limiter slot either, or a script could deny a real visitor their own link by
 * burning the budget on garbage.
 *
 * The limiter is keyed per (client address, share token) - see the budget
 * constants above for why the token is part of the key.
 */
private fun guard(call: ApplicationCall, body: Boolean): String {
    val token = call.parameters["token"]
        ?: throw ApiException.notFound("链接无效或已过期")
    if (!TOKEN_RE.matches(token)) throw ApiException.notFound("链接无效或已过期")
    val budget = if (body) SHARE_PUBLIC_BODY_BUDGET else SHARE_PUBLIC_META_BUDGET
    val key = "sharepub:" + call.clientIp() + ":" + token
    if (!Throttle.allow(key, budget, SHARE_PUBLIC_WINDOW_MS)) {
        throw ApiException.rateLimited()
    }
    return token
}
