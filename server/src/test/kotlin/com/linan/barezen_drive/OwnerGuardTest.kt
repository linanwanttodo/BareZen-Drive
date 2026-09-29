package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.SettingsTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.system.ServerSettingsService
import com.linan.barezen_drive.system.ownerAccountId
import com.linan.barezen_drive.system.ownerId
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who administers the instance.
 *
 * Ownership used to be re-derived on every request - "earliest createdAt, id as
 * the tie-break" - which is a query on an unindexed column on the request path,
 * and it answers a question about the *past*: the first account to be created
 * wins, forever. On a fresh instance that also hands the instance to whoever
 * reaches the published port first (see RegistrationSettingTest for the switch
 * that stops it).
 *
 * The owner id is now fixed in the settings table when the first account is
 * created, and the guard reads that value. These tests pin both halves: the pin
 * wins over a later "earlier" account, and an instance that predates the pin
 * still recognises its own first account - an upgrade must never lock the
 * existing owner out of their own instance.
 */
class OwnerGuardTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun cfg() = AppConfig(
        port = 0,
        jdbcUrl = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        dbUser = "sa", dbPassword = "",
        jwtSecret = "test-secret-0123456789abcdef0123456789abcdef",
        storageDir = Files.createTempDirectory("bz-ownerguard").toString(),
        maxFileSize = 1L shl 30,
        // Guests are created explicitly in these tests, so the registration
        // switch must not be what is under test.
        registrationOpen = true,
    )

    @AfterTest
    fun clearCache() = com.linan.barezen_drive.system.resetOwnerCache()

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
    }

    private fun insertUser(username: String, createdAt: Long): UUID {
        val id = UUID.randomUUID()
        transaction(DatabaseFactory.db) {
            UsersTable.insert {
                it[UsersTable.id] = id
                it[UsersTable.username] = username
                it[UsersTable.passwordHash] = "x"
                it[UsersTable.createdAt] = createdAt
            }
        }
        return id
    }

    private fun pinnedOwnerId(): UUID? = transaction(DatabaseFactory.db) {
        SettingsTable.selectAll().where { SettingsTable.key eq ServerSettingsService.KEY_OWNER_ID }.singleOrNull()
            ?.get(SettingsTable.value)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    private suspend fun ApplicationTestBuilder.register(user: String): String {
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$user","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$user","password":"password123"}""")
        }
        return "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    @Test
    fun theFirstAccountCreatedBecomesTheFixedOwner() = testApplication {
        setup()
        client.get("/health") // force the module to start
        val first = insertUser("first", 1_000L)
        // The guard is what pins it: reading the owner for the first time on an
        // instance that predates the setting adopts the earliest account.
        assertEquals(first, ownerId(), "the earliest existing account is adopted")
        assertEquals(first, pinnedOwnerId(), "and the answer is written down")

        // A later account cannot take ownership by being "earlier" in the table.
        insertUser("second", 500L)
        assertEquals(first, ownerId(), "a newer account never displaces the fixed owner")
        assertEquals(first, ownerAccountId(), "the transactional read agrees")
    }

    @Test
    fun thePinIsWrittenWhenTheFirstAccountRegisters() = testApplication {
        setup()
        val token = register("owner1")
        val pinned = pinnedOwnerId()
        assertNotNull(pinned, "creating the first account must fix the owner in the settings table")
        val me = client.get("/api/me") { header(HttpHeaders.Authorization, token) }
        val id = UUID.fromString(Regex(""""id":"([^"]+)"""").find(me.bodyAsText())!!.groupValues[1])
        assertEquals(id, pinned, "the account that registered first is the one that was pinned")
        assertTrue(json.decodeFromString<com.linan.barezen_drive.core.dto.UserDto>(me.bodyAsText()).isOwner)
    }

    @Test
    fun anExistingInstanceKeepsItsOwnerAfterTheUpgrade() = testApplication {
        setup()
        client.get("/health")
        // Exactly the state a pre-existing install is in: users, no owner_id row.
        val legacyOwner = insertUser("legacy", 1_700_000_000_000L)
        insertUser("guest", 1_700_000_001_000L)
        assertNull(pinnedOwnerId(), "precondition: the setting is absent before the first owner-scoped read")

        // The owner must still be able to administer, and the guest must not.
        assertEquals(legacyOwner, ownerId())
        val guestToken = register("guest2")
        val admin = client.get("/api/admin/users") { header(HttpHeaders.Authorization, guestToken) }
        assertEquals(HttpStatusCode.Forbidden, admin.status, admin.bodyAsText())
    }

    /**
     * The pin is a plain insert, so two racing first registrations cannot both
     * decide they own the instance: the loser reads back the winner instead of
     * overwriting it.
     */
    @Test
    fun claimingOwnershipTwiceKeepsTheFirstClaim() = testApplication {
        setup()
        client.get("/health")
        val first = insertUser("first", 1_000L)
        val winner = ServerSettingsService.claimOwner(first)
        assertEquals(first, winner)
        val second = insertUser("second", 2_000L)
        assertEquals(first, ServerSettingsService.claimOwner(second), "a second claim does not overwrite the first")
        assertEquals(first, pinnedOwnerId())
    }

    /**
     * The pin does not change on every request: the answer is held in process
     * (the users table only ever grows, and the pin is immutable), so the
     * management endpoints do not run a query per call. Dropping the row behind
     * the guard's back must not change what it answers.
     */
    @Test
    fun theOwnerIdIsHeldInProcess() = testApplication {
        setup()
        client.get("/health")
        val first = insertUser("first", 1_000L)
        assertEquals(first, ownerId())
        transaction(DatabaseFactory.db) {
            SettingsTable.deleteWhere { SettingsTable.key eq ServerSettingsService.KEY_OWNER_ID }
            Unit
        }
        // No row, no re-derivation, no query: the cached answer stands.
        assertEquals(first, ownerId())
        assertNull(pinnedOwnerId(), "precondition: the row really is gone")
        // Dropping the cache (as a restart would) goes back to the database.
        com.linan.barezen_drive.system.resetOwnerCache()
        assertEquals(first, ownerId(), "a cold cache still resolves the same owner")
    }

    @Test
    fun anInstanceWithoutAnyAccountHasNoOwner() = testApplication {
        setup()
        client.get("/health")
        assertNull(ownerId())
    }
}
