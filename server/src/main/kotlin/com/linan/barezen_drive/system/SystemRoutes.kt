package com.linan.barezen_drive.system

import com.linan.barezen_drive.config.AppConfig
import io.ktor.server.application.*
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
 * Public version endpoint. Must stay outside any authenticate block: clients
 * poll it before login to warn about a server/client version mismatch, and the
 * payload only contains public build identity (no user data).
 */
fun Route.versionRoutes(cfg: AppConfig) {
    get("/api/version") {
        val info = withContext(Dispatchers.IO) {
            VersionService.snapshot(cfg.updateRepoUrl, cfg.githubToken)
        }
        call.respond(info)
    }
}
