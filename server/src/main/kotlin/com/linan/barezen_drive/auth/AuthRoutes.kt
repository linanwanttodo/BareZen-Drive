package com.linan.barezen_drive.auth

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.Throttle
import com.linan.barezen_drive.api.TrustedProxyRange
import com.linan.barezen_drive.api.clientIp
import com.linan.barezen_drive.api.normalizePeerHost
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
import io.ktor.server.plugins.origin
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

/** Loopback, the only peer allowed to take the install-time bootstrap slot. */
private val LOOPBACK_RANGES = listOfNotNull(
    TrustedProxyRange.parse("127.0.0.0/8"),
    TrustedProxyRange.parse("::1/128"),
)

/**
 * True for the one account an instance with no accounts yet may still create:
 * a request that came straight from the machine the server runs on.
 *
 * Registration is closed by default, and the owner is whoever exists first, so
 * a public instance left in that state would be taken over by the first stranger
 * to press the register button - and that account can then list every user,
 * delete any other account and reopen registration. The install wizard
 * (BOOTSTRAP_ADMIN_*) is the normal way to create the owner; this is the manual
 * equivalent for a hand-rolled deployment, a `docker compose exec` or an SSH
 * tunnel.
 *
 * Two conditions keep it from being a public back door:
 *  - the peer must be a loopback address, so it only works from the host, and
 *  - a forwarding header disqualifies the request: when something upstream
 *    annotates the request, this instance cannot tell that something from the
 *    public internet, so the bootstrap slot stays closed and the operator has to
 *    set BOOTSTRAP_ADMIN_USER/PASSWORD instead.
 */
private suspend fun ApplicationCall.isHostBootstrapAttempt(): Boolean {
    if (Throttle.trustedProxyRanges.isNotEmpty()) return false
    val forwarded = request.headers["X-Forwarded-For"]
    if (!forwarded.isNullOrBlank()) return false
    // remoteAddress, not remoteHost: the latter is reverse-DNS resolved, so
    // "localhost" would not even parse as an address here. normalizePeerHost
    // maps the engines that report the local host by name back to 127.0.0.1.
    val peer = normalizePeerHost(runCatching { request.origin.remoteAddress }.getOrDefault(""))
    if (!LOOPBACK_RANGES.any { it.contains(peer) }) return false
    // An instance that already has an account is past installation; the owner
    // decides who else may join.
    return !withContext(Dispatchers.IO) { ServerSettingsService.hasAnyAccount() }
}

fun Route.authRoutes() {
    route("/api/auth") {
        post("/register") {
            // Throttle before touching the DB so a flood cannot exhaust the pool
            // or brute-force account enumeration.
            if (!Throttle.allow("reg:" + call.clientIp(), 5, 300_000)) {
                throw ApiException.rateLimited()
            }
            // A self-hosted drive usually wants exactly one account: the owner
            // signs up through the install wizard (or a local curl) and then
            // opens registration from the settings screen. The endpoint answers
            // 403 REGISTRATION_DISABLED until then.
            if (!ServerSettingsService.registrationOpen() && !call.isHostBootstrapAttempt()) {
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
            val account = req.username.trim().lowercase()
            // Second, independent axis: rotating source addresses (or a shared
            // NAT) must not buy unlimited guesses against one account. The unit
            // is claimed before the attempt and given back on success, so only
            // attempts that really happen spend the budget, and the claim is
            // atomic - peek-then-record let a burst of parallel requests all
            // read the same count before any of them wrote it and every one of
            // them got a guess.
            val lockKey = "login-account:$account"
            if (!Throttle.tryConsume(lockKey, LOGIN_FAILURES_PER_ACCOUNT, 900_000)) {
                throw ApiException.rateLimited()
            }
            val result = runCatching { withContext(Dispatchers.IO) { AuthService.login(req.username, req.password) } }
            // A failure KEEPS its unit - that is what the budget is for.
            val response = result.getOrElse { throw it }
            // A household where several people log in as the same account must
            // not lock itself out on honest logins.
            Throttle.refund(lockKey, 900_000)
            call.respond(response)
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
