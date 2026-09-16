package com.linan.barezen_drive.auth

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.Throttle
import com.linan.barezen_drive.api.clientIp
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

/** Failed sign-ins allowed per username inside the account window. */
private const val LOGIN_FAILURES_PER_ACCOUNT = 20

fun Route.authRoutes() {
    route("/api/auth") {
        post("/register") {
            // Throttle before touching the DB so a flood cannot exhaust the pool
            // or brute-force account enumeration.
            if (!Throttle.allow("reg:" + call.clientIp(), 5, 300_000)) {
                throw ApiException.rateLimited()
            }
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
            if (!Throttle.allow("login:" + call.clientIp(), 10, 60_000)) {
                throw ApiException.rateLimited()
            }
            val req = call.receive<LoginRequest>()
            // Second, independent axis: rotating source addresses (or a shared
            // NAT) must not buy unlimited guesses against one account. Only
            // failures spend this budget, so a household where several people
            // log in as the same user is unaffected.
            val account = req.username.trim().lowercase()
            if (Throttle.peek("login-account:$account", 900_000) >= LOGIN_FAILURES_PER_ACCOUNT) {
                throw ApiException.rateLimited()
            }
            val result = runCatching { withContext(Dispatchers.IO) { AuthService.login(req.username, req.password) } }
            if (result.isFailure) Throttle.record("login-account:$account", 900_000)
            call.respond(result.getOrThrow())
        }
        post("/refresh") {
            if (!Throttle.allow("refresh:" + call.clientIp(), 30, 60_000)) {
                throw ApiException.rateLimited()
            }
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
