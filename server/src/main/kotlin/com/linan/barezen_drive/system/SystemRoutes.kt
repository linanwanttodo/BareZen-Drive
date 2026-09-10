package com.linan.barezen_drive.system

import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.RegistrationSettingRequest
import com.linan.barezen_drive.core.dto.RegistrationStatusDto
import com.linan.barezen_drive.core.dto.ServerStatsDto
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 */
fun Route.versionRoutes(cfg: AppConfig) {
    get("/api/version") {
        val info = withContext(Dispatchers.IO) {
            VersionService.snapshot(cfg.updateRepoUrl, cfg.updateManifestUrl, cfg.githubToken)
        }
        call.respond(info)
    }
}
