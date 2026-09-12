package com.linan.barezen_drive.auth

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.core.dto.ErrorCodes
import com.linan.barezen_drive.core.dto.LoginResponse
import com.linan.barezen_drive.core.dto.RefreshResponse
import com.linan.barezen_drive.core.dto.UserDto
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.RefreshTokensTable
import com.linan.barezen_drive.db.UsersTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID

// Timestamp columns are epoch-millis Long (see Tables.kt); convert to
// ISO-8601 strings only at DTO boundaries.
object AuthService {
    private val REFRESH_TTL_MS = Duration.ofDays(30).toMillis()
    private val rnd = SecureRandom()
    private val USERNAME_RE = Regex("^[a-zA-Z0-9_]{3,32}$")

    private fun hashToken(t: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(t.encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun epochToIso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    internal fun toUserDto(r: ResultRow) = UserDto(
        r[UsersTable.id].toString(),
        r[UsersTable.username],
        epochToIso(r[UsersTable.createdAt]),
    )

    private fun newRefreshToken(): Pair<String, Long> {
        val bytes = ByteArray(32)
        rnd.nextBytes(bytes)
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return token to (System.currentTimeMillis() + REFRESH_TTL_MS)
    }

    fun register(username: String, password: String): UserDto {
        if (!USERNAME_RE.matches(username)) {
            throw ApiException.badRequest("用户名需 3-32 位字母数字下划线", ErrorCodes.USERNAME_INVALID)
        }
        if (password.length < 8) throw ApiException.badRequest("密码至少 8 位", ErrorCodes.PASSWORD_TOO_SHORT)
        // favre BCrypt rejects raw passwords longer than 72 bytes outright;
        // surface it as a 400 instead of letting it bubble up as a 500.
        if (password.encodeToByteArray().size > 72) {
            throw ApiException.badRequest("密码过长（最多 72 字节）", ErrorCodes.VALIDATION_ERROR)
        }
        return transaction(DatabaseFactory.db) {
            if (UsersTable.selectAll().where { UsersTable.username eq username }.any()) {
                throw ApiException.conflict(ErrorCodes.USERNAME_TAKEN, "用户名已存在")
            }
            val id = UUID.randomUUID()
            val now = System.currentTimeMillis()
            UsersTable.insert {
                it[UsersTable.id] = id
                it[UsersTable.username] = username
                it[passwordHash] = PasswordHasher.hash(password)
                it[createdAt] = now
            }
            UserDto(id.toString(), username, epochToIso(now))
        }
    }

    /**
     * Creates the owner account from the BOOTSTRAP_ADMIN_USER/PASSWORD env
     * pair when the install wizard pre-configured one and no user exists yet.
     * The first account owns the instance, so this seed becomes it. Returns
     * null when users already exist or the pair is unset or invalid.
     */
    fun bootstrapAdmin(user: String?, password: String?): UserDto? {
        if (user.isNullOrBlank() || password.isNullOrBlank()) return null
        val hasUsers = transaction(DatabaseFactory.db) { UsersTable.selectAll().limit(1).any() }
        if (hasUsers) return null
        return runCatching { register(user, password) }.getOrNull()
    }

    fun login(username: String, password: String): LoginResponse = transaction(DatabaseFactory.db) {
        val row = UsersTable.selectAll().where { UsersTable.username eq username }.singleOrNull()
            ?: throw ApiException.unauthorized("用户名或密码错误")
        // A stored hash can never correspond to a >72-byte raw password (register
        // rejects those), so verify() would only throw; answer like a wrong password.
        val ok = password.encodeToByteArray().size <= 72 &&
            PasswordHasher.verify(password, row[UsersTable.passwordHash])
        if (!ok) throw ApiException.unauthorized("用户名或密码错误")
        issueTokens(row[UsersTable.id])
    }

    fun issueTokens(uid: UUID): LoginResponse {
        val (raw, expMs) = newRefreshToken()
        transaction(DatabaseFactory.db) {
            RefreshTokensTable.insert {
                it[id] = UUID.randomUUID()
                it[user] = uid
                it[tokenHash] = hashToken(raw)
                it[expiresAt] = expMs
            }
        }
        val user = transaction(DatabaseFactory.db) {
            val r = UsersTable.selectAll().where { UsersTable.id eq uid }.single()
            toUserDto(r)
        }
        return LoginResponse(JwtService.issue(uid.toString()), raw, user)
    }

    fun refresh(raw: String): RefreshResponse = transaction(DatabaseFactory.db) {
        val h = hashToken(raw)
        val row = RefreshTokensTable.selectAll().where { RefreshTokensTable.tokenHash eq h }.singleOrNull()
            ?: throw ApiException.unauthorized("refresh token 无效", ErrorCodes.TOKEN_INVALID)
        if (row[RefreshTokensTable.revokedAt] != null) {
            throw ApiException.unauthorized("refresh token 已失效", ErrorCodes.TOKEN_INVALID)
        }
        if (row[RefreshTokensTable.expiresAt] < System.currentTimeMillis()) {
            throw ApiException.unauthorized("refresh token 已过期", ErrorCodes.TOKEN_EXPIRED)
        }
        val uid = row[RefreshTokensTable.user]
        // Conditional revoke: if a concurrent refresh already took this token,
        // the update matches zero rows and we reject instead of issuing a second pair.
        val revoked = RefreshTokensTable.update({
            (RefreshTokensTable.tokenHash eq h) and (RefreshTokensTable.revokedAt eq null)
        }) { it[revokedAt] = System.currentTimeMillis() }
        if (revoked == 0) throw ApiException.unauthorized("refresh token 已失效", ErrorCodes.TOKEN_INVALID)
        val (newRaw, expMs) = newRefreshToken()
        RefreshTokensTable.insert {
            it[id] = UUID.randomUUID()
            it[user] = uid
            it[tokenHash] = hashToken(newRaw)
            it[expiresAt] = expMs
        }
        RefreshResponse(JwtService.issue(uid.toString()), newRaw)
    }
}
