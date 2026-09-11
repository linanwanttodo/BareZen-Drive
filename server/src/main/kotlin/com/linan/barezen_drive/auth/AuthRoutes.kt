package com.linan.barezen_drive.auth

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.core.dto.ErrorCodes
import com.linan.barezen_drive.core.dto.LoginRequest
import com.linan.barezen_drive.core.dto.RefreshRequest
import com.linan.barezen_drive.core.dto.RegisterRequest
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.system.ServerSettingsService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

val ApplicationCall.userId: UUID
    get() = principal<JWTPrincipal>()?.payload?.getClaim("sub")?.asString()?.let { UUID.fromString(it) }
        ?: throw ApiException.unauthorized("未登录", ErrorCodes.TOKEN_INVALID)

fun Route.authRoutes() {
    route("/api/auth") {
        post("/register") {
            // A self-hosted drive usually wants exactly one account: after the
            // owner signs up they close registration from the settings screen,
            // and the endpoint answers 403 REGISTRATION_DISABLED until reopened.
            if (!ServerSettingsService.registrationOpen()) {
                throw ApiException.forbidden("注册已关闭", ErrorCodes.REGISTRATION_DISABLED)
            }
            val req = call.receive<RegisterRequest>()
            call.respond(HttpStatusCode.Created, withContext(Dispatchers.IO) { AuthService.register(req.username, req.password) })
        }
        post("/login") {
            val req = call.receive<LoginRequest>()
            call.respond(withContext(Dispatchers.IO) { AuthService.login(req.username, req.password) })
        }
        post("/refresh") {
            val req = call.receive<RefreshRequest>()
            call.respond(withContext(Dispatchers.IO) { AuthService.refresh(req.refreshToken) })
        }
    }
    authenticate("auth-jwt") {
        get("/api/me") {
            val uid = call.userId
            val user = withContext(Dispatchers.IO) {
                transaction(DatabaseFactory.db) {
                    // A deleted account keeps a valid-looking JWT until expiry;
                    // treat the missing row as an invalid session, not a 500.
                    val row = UsersTable.selectAll().where { UsersTable.id eq uid }.singleOrNull()
                        ?: throw ApiException.unauthorized("未登录或 token 无效", ErrorCodes.TOKEN_INVALID)
                    AuthService.toUserDto(row)
                }
            }
            call.respond(user)
        }
    }
}
