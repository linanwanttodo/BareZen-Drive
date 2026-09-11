package com.linan.barezen_drive

import com.linan.barezen_drive.auth.JwtService
import com.linan.barezen_drive.auth.LinkService
import com.linan.barezen_drive.auth.authRoutes
import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.ApiError
import com.linan.barezen_drive.core.dto.ErrorCodes
import com.linan.barezen_drive.core.dto.ErrorResponse
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.files.fileContentRoutes
import com.linan.barezen_drive.files.folderRoutes
import com.linan.barezen_drive.files.shareOwnerRoutes
import com.linan.barezen_drive.files.sharePublicRoutes
import com.linan.barezen_drive.files.thumbnailRoutes
import com.linan.barezen_drive.files.uploadRoutes
import com.linan.barezen_drive.system.adminUserRoutes
import com.linan.barezen_drive.system.settingsRoutes
import com.linan.barezen_drive.system.systemRoutes
import com.linan.barezen_drive.system.versionRoutes
import com.linan.barezen_drive.jobs.UploadCleanupJob
import com.linan.barezen_drive.plugins.staticWeb
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

fun main() {
    val cfg = AppConfig.fromEnv()
    val storage: StorageProvider = LocalStorageProvider(java.nio.file.Path.of(cfg.storageDir))
    // Cleanup loop must start BEFORE start(wait = true) blocks the main thread; it runs in
    // production only, never in tests (module() does not spawn it).
    CoroutineScope(Dispatchers.Default + SupervisorJob()).let { UploadCleanupJob.start(it, storage) }
    embeddedServer(Netty, port = cfg.port) { module(cfg, storage) }.start(wait = true)
}

fun Application.module(cfg: AppConfig, storage: StorageProvider) {
    JwtService.init(cfg.jwtSecret)
    LinkService.init(cfg.jwtSecret)
    // Connect + schema DDL inside module() so testApplication exercises it too.
    // Guarded so repeated testApplication entry reuses the Exposed connection.
    connectDatabaseOnce(cfg)
    // encodeDefaults=true keeps the public API shape stable: fields with
    // default values (updateAvailable, assets, hasThumbnail...) are always
    // present in responses instead of silently omitted.
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(CallLogging)
    install(PartialContent)
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.error))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCodes.VALIDATION_ERROR, "请求体格式错误")))
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCodes.VALIDATION_ERROR, "请求体格式错误")))
        }
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ApiError(ErrorCodes.VALIDATION_ERROR, "请求体格式错误")))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("unhandled", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ApiError(ErrorCodes.INTERNAL_ERROR, "服务器内部错误")))
        }
    }
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "barezen"
            verifier(JwtService.verifier)
            validate { cred -> cred.payload.getClaim("sub")?.asString()?.let { JWTPrincipal(cred.payload) } }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ApiError(ErrorCodes.TOKEN_INVALID, "未登录或 token 无效")))
            }
        }
    }
    routing {
        get("/health") { call.respondText("""{"status":"ok"}""", ContentType.Application.Json) }
        // Public: clients and the web bundle compare versions before login.
        versionRoutes(cfg)
        // Registration status is public (login screen hides the register tab);
        // the toggle itself lives behind authentication inside settingsRoutes.
        settingsRoutes()
        // User management authenticates internally (owner model); registered
        // outside any authenticate block or the nested guard answers 500.
        adminUserRoutes(storage)
        authRoutes()
        // Optional: content/thumbnail GET accept a valid signature as an alternative
        // to Bearer (browser tabs, players). Strict endpoints inside check call.userId
        // themselves, which throws 401 when no principal is present.
        authenticate("auth-jwt", optional = true) {
            fileContentRoutes(storage)
            thumbnailRoutes(storage)
        }
        authenticate("auth-jwt") {
            folderRoutes(storage)
            uploadRoutes(storage)
            systemRoutes(cfg.storageDir)
            shareOwnerRoutes()
        }
        // Public share endpoints must stay outside any authenticate block:
        // share visitors have no account.
        sharePublicRoutes(storage)
        staticWeb()
    }
}

private fun Application.connectDatabaseOnce(cfg: AppConfig) {
    if (attributes.getOrNull(DB_CONNECTED_KEY) == true) return
    synchronized(DatabaseFactory) {
        if (attributes.getOrNull(DB_CONNECTED_KEY) == true) return
        DatabaseFactory.connect(cfg.jdbcUrl, cfg.dbUser, cfg.dbPassword)
        attributes.put(DB_CONNECTED_KEY, true)
    }
}

private val DB_CONNECTED_KEY = AttributeKey<Boolean>("BareZenDbConnected")
