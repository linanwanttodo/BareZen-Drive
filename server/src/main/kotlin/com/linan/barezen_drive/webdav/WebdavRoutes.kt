package com.linan.barezen_drive.webdav

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.WebdavTokenCreateRequest
import com.linan.barezen_drive.core.dto.WebdavTokenCreatedResponse
import com.linan.barezen_drive.core.dto.WebdavTokenDto
import com.linan.barezen_drive.core.dto.WebdavTokensResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * App-password management for mounted drives.
 *
 * Ordinary Bearer endpoints under /api, not part of the WebDAV surface: a mount
 * cannot manage its own credentials (it has no way to prompt for a new one), and
 * keeping them here means a headless install can mint a token with curl against
 * an ordinary authenticated endpoint.
 */
fun Route.webdavTokenRoutes() {
    authenticate("auth-jwt") {
        route("/api/webdav/tokens") {
            get {
                call.respond(
                    WebdavTokensResponse(
                        WebdavTokenService.list(call.userId).map {
                            WebdavTokenDto(it.id, it.label, it.readOnly, it.createdAt, it.lastUsedAt)
                        },
                    ),
                )
            }

            post {
                val req = call.receive<WebdavTokenCreateRequest>()
                val label = req.label.trim()
                if (label.isEmpty() || label.length > WebdavTokenService.MAX_LABEL_LENGTH) {
                    throw ApiException.badRequest("名称必须为 1–${WebdavTokenService.MAX_LABEL_LENGTH} 个字符")
                }
                val (view, plaintext) = WebdavTokenService.create(call.userId, label, req.readOnly)
                // The only response that ever carries the secret. It is not
                // logged and not retrievable afterwards, so the UI has to show
                // it once and say so.
                call.respond(
                    HttpStatusCode.Created,
                    WebdavTokenCreatedResponse(
                        WebdavTokenDto(view.id, view.label, view.readOnly, view.createdAt, view.lastUsedAt),
                        plaintext,
                    ),
                )
            }

            delete("/{id}") {
                // Scoped by user, so another account's token id answers 404
                // rather than confirming that it exists.
                if (!WebdavTokenService.revoke(call.userId, call.parameters["id"]!!.toUuidOrBadRequest())) {
                    throw ApiException.notFound("应用密码不存在")
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
