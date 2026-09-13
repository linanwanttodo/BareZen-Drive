package com.linan.barezen_drive.system

import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.RegistrationSettingRequest
import com.linan.barezen_drive.core.dto.RegistrationStatusDto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun Route.systemRoutes(storageDir: String) {
    get("/api/server/stats") {
        val stats = withContext(Dispatchers.IO) { SystemStatsService.snapshot(storageDir) }
        call.respond(stats)
    }
}

/**
 * Owner-managed server settings.
 *
 * GET is public: the login screen reads it to hide the register tab when
 * sign-ups are closed, so a visitor never fills a form that would only fail.
 * PATCH is owner-only: any signed-in user of this single-user deployment may
 * toggle registration, matching the rest of the server's single-tenant model.
 */
fun Route.settingsRoutes() {
    get("/api/settings/registration") {
        val open = withContext(Dispatchers.IO) { ServerSettingsService.registrationOpen() }
        call.respond(RegistrationStatusDto(open))
    }
    authenticate("auth-jwt") {
        patch("/api/settings/registration") {
            call.userId // enforce authentication before touching anything
            val req = call.receive<RegistrationSettingRequest>()
            withContext(Dispatchers.IO) { ServerSettingsService.setRegistrationOpen(req.open) }
            call.respond(RegistrationStatusDto(req.open))
        }
    }
}

/**
 * Public version endpoint. Must stay outside any authenticate block: clients
 * poll it before login to warn about a server/client version mismatch, and the
 * payload only contains public build identity (no user data).
 *
 * Manifest asset links are rewritten to this server's own download proxy
 * ([proxyRoutes]) with the upstream link kept as [fallbackUrl][com.linan.barezen_drive.core.dto.UpdateAssetDto.fallbackUrl]:
 * the phone then tries the proxy first - one hop to a host it already talks to
 * - and falls back to the direct link, so an update downloads no matter which
 * of the two hops (phone-to-server, server-to-GitHub) is the flaky one.
 */
fun Route.versionRoutes(cfg: AppConfig) {
    get("/api/version") {
        val info = withContext(Dispatchers.IO) {
            VersionService.snapshot(cfg.updateRepoUrl, cfg.updateManifestUrl, cfg.githubToken)
        }
        call.respond(rewriteAssetsToProxy(call.request.origin, info))
    }
}

/**Streams GitHub release packages through this server for update clients. */
fun Route.proxyRoutes(cfg: AppConfig) {
    get("/api/updates/download/{tag}/{file}") {
        val tag = call.parameters["tag"].orEmpty()
        val file = call.parameters["file"].orEmpty()
        val tagOk = tag.matches(Regex("[A-Za-z0-9._-]+"))
        // Fixed extension set: an update proxy must never become an open path
        // relay, and the query string is rejected so it cannot smuggle arguments.
        val fileOk = file.matches(Regex("[A-Za-z0-9._-]+\\.(apk|zip|tar\\.gz|tgz|json|deb|rpm|dmg|msi)"))
        if (!tagOk || !fileOk) {
            call.respond(HttpStatusCode.BadRequest, "invalid download path")
            return@get
        }
        val repoPath = githubRepoPath(cfg.updateRepoUrl)
        if (repoPath == null) {
            call.respond(HttpStatusCode.InternalServerError, "update repository not configured")
            return@get
        }
        val upstream = "https://github.com/$repoPath/releases/download/$tag/$file"
        withContext(Dispatchers.IO) {
            runCatching {
                val request = HttpRequest.newBuilder(URI.create(upstream))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "BareZen-Drive")
                    .apply {
                        // Byte-range requests pass through: a resumable
                        // download needs a 206 over the exact span it asked
                        // for, not a silent full-body 200 from the relay.
                        call.request.header(HttpHeaders.Range)?.let { header(HttpHeaders.Range, it) }
                    }
                    .GET()
                    .build()
                val response = proxyClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
                val status = response.statusCode()
                when (status) {
                    200, 206 -> {}
                    416 -> {
                        response.body().close()
                        call.respond(HttpStatusCode.RequestedRangeNotSatisfiable, "range not satisfiable")
                        return@runCatching
                    }
                    else -> {
                        response.body().close()
                        call.respond(HttpStatusCode.BadGateway, "upstream $status")
                        return@runCatching
                    }
                }
                response.headers().firstValue(HttpHeaders.ContentLength).ifPresent {
                    call.response.header(HttpHeaders.ContentLength, it)
                }
                if (status == 206) {
                    response.headers().firstValue(HttpHeaders.ContentRange).ifPresent {
                        call.response.header(HttpHeaders.ContentRange, it)
                    }
                }
                val contentType = response.headers().firstValue(HttpHeaders.ContentType).orElse("application/octet-stream")
                val respondStatus = if (status == 206) HttpStatusCode.PartialContent else HttpStatusCode.OK
                call.respondBytesWriter(status = respondStatus, contentType = ContentType.parse(contentType)) {
                    response.body().toByteReadChannel().copyTo(this)
                }
            }.onFailure {
                runCatching { call.respond(HttpStatusCode.BadGateway, "proxy failed") }
            }
        }
    }
}

/** Shared client for the update proxy; redirects (CDN hops) are followed. */
private val proxyClient: HttpClient by lazy {
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
}

/** owner/repo from a repository URL, or null when it is not GitHub. */
private fun githubRepoPath(repoUrl: String): String? {
    val idx = repoUrl.indexOf("github.com/")
    if (idx < 0) return null
    val parts = repoUrl.substring(idx + "github.com/".length).trim().trimEnd('/').removeSuffix(".git").split('/')
    return if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) "${parts[0]}/${parts[1]}" else null
}
