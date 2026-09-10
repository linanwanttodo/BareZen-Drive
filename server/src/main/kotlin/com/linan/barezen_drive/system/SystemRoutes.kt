package com.linan.barezen_drive.system

import com.linan.barezen_drive.auth.userId
import com.linan.barezen_drive.core.dto.ServerStatsDto
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
