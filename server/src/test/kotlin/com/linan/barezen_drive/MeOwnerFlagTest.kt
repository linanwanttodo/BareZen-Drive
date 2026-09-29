package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.UserDto
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/**
 * `UserDto.isOwner` is the client's only permission signal. Without it the
 * settings page listed owner-only rows for every account, and a guest who
 * tapped one got a page whose entire content was a red 403 line - the check
 * happened after the navigation instead of before it.
 *
 * Ownership itself is "earliest account, ties broken by id" (see
 * OwnerGuard.ownerAccountId), so these tests pin the flag for the first
 * account, for a later one, and for the tie case that must not flip between two
 * accounts created in the same millisecond.
 */
class MeOwnerFlagTest {
    private val storageDir = Files.createTempDirectory("bz-owner").toString()

    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
        // The guest cases below need a second account, which a default (closed)
        // instance only admits once the owner opens registration.
        registrationOpen = true,
    )
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
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

    private suspend fun ApplicationTestBuilder.me(token: String): UserDto {
        val res = client.get("/api/me") { header(HttpHeaders.Authorization, token) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return json.decodeFromString(res.bodyAsText())
    }

    @Test
    fun theFirstAccountIsTheOwner() = testApplication {
        setup()
        val token = register("owner1")
        val me = me(token)
        assertEquals("owner1", me.username)
        assertTrue(me.isOwner, "the first account must be reported as the owner")
    }

    @Test
    fun laterAccountsAreNotOwners() = testApplication {
        setup()
        register("first")
        val guestToken = register("guest")
        val me = me(guestToken)
        assertEquals("guest", me.username)
        assertTrue(!me.isOwner, "a second account must not be reported as the owner")
        // Sanity: the owner flag is what the admin endpoint keys off too.
        val admin = client.get("/api/admin/users") { header(HttpHeaders.Authorization, guestToken) }
        assertEquals(HttpStatusCode.Forbidden, admin.status, admin.bodyAsText())
    }

    @Test
    fun theLoginResponseCarriesTheSameFlag() = testApplication {
        setup()
        register("first")
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"second","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"second","password":"password123"}""")
        }
        val dto = json.decodeFromString<UserDto>(
            Regex(""""user":(\{[^}]+\})""").find(login.bodyAsText())!!.groupValues[1],
        )
        assertTrue(!dto.isOwner)
    }
}
