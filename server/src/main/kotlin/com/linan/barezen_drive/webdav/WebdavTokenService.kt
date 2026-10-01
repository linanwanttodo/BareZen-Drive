package com.linan.barezen_drive.webdav

import com.linan.barezen_drive.auth.TokenSecret
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.db.WebdavTokensTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/** A credential that authenticated a WebDAV request, as the routes need it. */
data class WebdavTokenRecord(val userId: UUID, val readOnly: Boolean, val id: UUID)

/**
 * What the settings list shows. Carries no secret: the plaintext exists in
 * exactly one create response and is unrecoverable afterwards.
 */
data class WebdavTokenView(
    val id: String,
    val label: String,
    val readOnly: Boolean,
    val createdAt: Long,
    val lastUsedAt: Long,
)

/**
 * App passwords for mounted drives.
 *
 * One credential per device, revocable on its own: a WebDAV client keeps the
 * password in a plaintext config or a system keychain, so the only way to
 * contain a leak is to be able to cut one device off without disturbing the
 * others. [WebdavTokensTable] explains why the lookup is an exact hash match
 * rather than a KDF.
 */
object WebdavTokenService {
    const val MAX_LABEL_LENGTH = 64

    suspend fun create(
        userId: UUID,
        label: String,
        readOnly: Boolean,
    ): Pair<WebdavTokenView, String> = withContext(Dispatchers.IO) {
        val token = TokenSecret.mint()
        val id = UUID.randomUUID()
        val now = System.currentTimeMillis()
        val view = transaction(DatabaseFactory.db) {
            WebdavTokensTable.insert {
                it[WebdavTokensTable.id] = id
                it[WebdavTokensTable.user] = userId
                it[WebdavTokensTable.tokenHash] = TokenSecret.hash(token)
                it[WebdavTokensTable.label] = label
                it[WebdavTokensTable.readOnly] = readOnly
                it[createdAt] = now
                it[lastUsedAt] = 0L
            }
            WebdavTokenView(id.toString(), label, readOnly, now, 0L)
        }
        view to token
    }

    suspend fun list(userId: UUID): List<WebdavTokenView> = withContext(Dispatchers.IO) {
        transaction(DatabaseFactory.db) {
            WebdavTokensTable.selectAll()
                .where { WebdavTokensTable.user eq userId }
                .orderBy(WebdavTokensTable.createdAt to SortOrder.DESC)
                .map { row ->
                    WebdavTokenView(
                        id = row[WebdavTokensTable.id].toString(),
                        label = row[WebdavTokensTable.label],
                        readOnly = row[WebdavTokensTable.readOnly],
                        createdAt = row[WebdavTokensTable.createdAt],
                        lastUsedAt = row[WebdavTokensTable.lastUsedAt],
                    )
                }
        }
    }

    /**
     * True only when a row was really removed, so the route can answer 404 for a
     * token that is not the caller's without revealing that it exists at all.
     */
    suspend fun revoke(userId: UUID, tokenId: UUID): Boolean = withContext(Dispatchers.IO) {
        transaction(DatabaseFactory.db) {
            WebdavTokensTable.deleteWhere {
                (WebdavTokensTable.id eq tokenId) and (WebdavTokensTable.user eq userId)
            } > 0
        }
    }

    /**
     * Resolve HTTP Basic credentials to a mounted-drive credential.
     *
     * The username has to match the token's owner, not just any account: Basic
     * sends both halves on every request, and a token is bound to the user it
     * was minted for. Checking only the hash would let a valid token mounted
     * under someone else's name resolve to *its* owner, which is confusing at
     * best and a misconfigured-mount footgun at worst.
     *
     * Both lookups share one transaction on purpose. Opening a second one would
     * ask the pool for another connection while holding the first, and the pool
     * is deliberately five wide.
     */
    suspend fun authenticate(username: String, password: String): WebdavTokenRecord? =
        withContext(Dispatchers.IO) {
            val wanted = TokenSecret.hash(password)
            transaction(DatabaseFactory.db) {
                val row = WebdavTokensTable.selectAll()
                    .where { WebdavTokensTable.tokenHash eq wanted }
                    .firstOrNull() ?: return@transaction null

                val userId = row[WebdavTokensTable.user]
                val owner = UsersTable.selectAll()
                    .where { UsersTable.id eq userId }
                    .firstOrNull()?.get(UsersTable.username)
                if (owner != username) return@transaction null

                // Best effort: a mount that cannot record its own use is still a
                // valid mount, so this must not be able to fail the request.
                runCatching {
                    WebdavTokensTable.update({ WebdavTokensTable.id eq row[WebdavTokensTable.id] }) {
                        it[lastUsedAt] = System.currentTimeMillis()
                    }
                }

                WebdavTokenRecord(userId, row[WebdavTokensTable.readOnly], row[WebdavTokensTable.id])
            }
        }
}
