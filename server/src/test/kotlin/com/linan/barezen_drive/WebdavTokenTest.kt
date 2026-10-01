package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.db.WebdavTokensTable
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A mounted drive authenticates on every single request, so what it sends has
 * to be cheap to verify and cheap to revoke. That is the whole reason this is a
 * separate credential rather than the account password: the password is a
 * bcrypt hash (cost 10, ~100ms) and opening one folder is hundreds of requests.
 *
 * These tests pin the three properties that make it safe to store a plain
 * exact-match hash instead of a KDF: the token is unguessable, it is revocable on
 * its own, and it authenticates nothing but the WebDAV surface.
 */
class WebdavTokenTest {
    private val storageDir = Files.createTempDirectory("bz-davtok").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""

    private fun basic(user: String, pass: String) =
        "Basic " + "$user:$pass".toByteArray().encodeBase64()

    private suspend fun ApplicationTestBuilder.setup() {
        val c = AppConfig(
            0,
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
            registrationOpen = true,
        )
        application { module(c, LocalStorageProvider(Path.of(c.storageDir))) }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""")
            .find(client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"user1","password":"password123"}""")
            }.bodyAsText())!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.createToken(
        label: String = "iPad",
        readOnly: Boolean = false,
    ) = client.post("/api/webdav/tokens") {
        header(HttpHeaders.Authorization, auth)
        contentType(ContentType.Application.Json)
        setBody("""{"label":${json.encodeToString(label)},"readOnly":$readOnly}""")
    }

    /** A freshly minted credential: the id for later revocation, the plaintext
     *  for authenticating with. The plaintext exists in exactly one response. */
    private data class Issued(val id: String, val plaintext: String)

    private suspend fun ApplicationTestBuilder.issue(
        label: String = "iPad",
        readOnly: Boolean = false,
    ): Issued {
        val body = json.parseToJsonElement(createToken(label, readOnly).bodyAsText()) as JsonObject
        val dto = body["token"] as JsonObject
        return Issued(
            id = dto["id"]!!.jsonPrimitive.content,
            plaintext = body["plaintext"]!!.jsonPrimitive.content,
        )
    }

    /** Just the plaintext, for tests that never need to revoke. */
    private suspend fun ApplicationTestBuilder.newToken(
        label: String = "iPad",
        readOnly: Boolean = false,
    ): String = issue(label, readOnly).plaintext

    @Test
    fun aTokenIsShownOnceAndOnlyItsHashRemains() = testApplication {
        setup()
        val created = createToken()
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val plaintext = (json.parseToJsonElement(created.bodyAsText()) as JsonObject)["plaintext"]!!
            .jsonPrimitive.content
        assertTrue(plaintext.length >= 32, "the token must be long enough not to be guessable")

        // The listing shows the label, never the token again.
        val list = client.get("/api/webdav/tokens") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
        assertTrue(list.bodyAsText().contains("iPad"), list.bodyAsText())
        assertFalse(list.bodyAsText().contains(plaintext), "the plaintext must not be retrievable")

        // And no row anywhere holds it in the clear.
        val stored = transaction {
            WebdavTokensTable.selectAll().map { it[WebdavTokensTable.tokenHash] }
        }
        assertEquals(1, stored.size)
        assertFalse(
            stored.single().contains(plaintext, ignoreCase = true),
            "the token must never be stored in the clear",
        )
    }

    @Test
    fun oneTokenAuthenticatesAndTheAccountPasswordDoesNot() = testApplication {
        setup()
        val token = newToken()

        // The resource routes arrive in a later task, so any non-401 proves the
        // credential authenticated; 401 is the only unacceptable answer.
        val ok = client.get("/dav/") { header(HttpHeaders.Authorization, basic("user1", token)) }
        assertNotEquals(HttpStatusCode.Unauthorized, ok.status, "the token must authenticate")

        val withPassword = client.get("/dav/") {
            header(HttpHeaders.Authorization, basic("user1", "password123"))
        }
        assertEquals(
            HttpStatusCode.Unauthorized, withPassword.status,
            "the account password must not work here",
        )
    }

    @Test
    fun theUsernameHasToMatchTheTokensOwner() = testApplication {
        setup()
        val token = newToken()
        val wrongUser = client.get("/dav/") {
            header(HttpHeaders.Authorization, basic("someone-else", token))
        }
        assertEquals(
            HttpStatusCode.Unauthorized, wrongUser.status,
            "a valid token presented under another name must not authenticate",
        )
    }

    @Test
    fun revokingOneTokenLeavesTheOthersAlone() = testApplication {
        setup()
        val mac = issue("mac")
        val ipad = issue("ipad")

        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/api/webdav/tokens/${mac.id}") {
                header(HttpHeaders.Authorization, auth)
            }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/dav/") { header(HttpHeaders.Authorization, basic("user1", mac.plaintext)) }.status,
        )
        assertNotEquals(
            HttpStatusCode.Unauthorized,
            client.get("/dav/") { header(HttpHeaders.Authorization, basic("user1", ipad.plaintext)) }.status,
            "revoking one device must not revoke the others",
        )
    }

    @Test
    fun revokingSomeoneElsesTokenIsNotFound() = testApplication {
        setup()
        // A second account mints a token; user1 must not be able to revoke it.
        // 404 rather than 403, because 403 would confirm the id exists.
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user2","password":"password123"}""")
        }
        val user2Auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""")
            .find(client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"user2","password":"password123"}""")
            }.bodyAsText())!!.groupValues[1]
        val theirBody = json.parseToJsonElement(
            client.post("/api/webdav/tokens") {
                header(HttpHeaders.Authorization, user2Auth)
                contentType(ContentType.Application.Json)
                setBody("""{"label":"theirs"}""")
            }.bodyAsText(),
        ) as JsonObject
        val theirs = theirBody["token"] as JsonObject
        val theirPlaintext = theirBody["plaintext"]!!.jsonPrimitive.content

        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/api/webdav/tokens/${theirs["id"]!!.jsonPrimitive.content}") {
                header(HttpHeaders.Authorization, auth)
            }.status,
            "another account's token must be 404, not 204 or 403",
        )
        // And it still works for its own owner, i.e. the failed revoke changed nothing.
        assertNotEquals(
            HttpStatusCode.Unauthorized,
            client.get("/dav/") {
                header(HttpHeaders.Authorization, basic("user2", theirPlaintext))
            }.status,
        )
    }

    @Test
    fun aTokenIsUselessAgainstTheRestApi() = testApplication {
        setup()
        val token = newToken()
        // Only real, authenticated paths: an unregistered path 404s for everyone,
        // which would make the assertion pass for the wrong reason.
        for (path in listOf("/api/me", "/api/admin/users", "/api/server/stats", "/api/folders/root/contents")) {
            val r = client.get(path) { header(HttpHeaders.Authorization, basic("user1", token)) }
            assertEquals(HttpStatusCode.Unauthorized, r.status, "$path must reject a WebDAV token")
        }
    }

    @Test
    fun tokenManagementItselfNeedsTheAccountSession() = testApplication {
        setup()
        // No Authorization header at all: these are ordinary Bearer endpoints, so
        // a mount credential is the wrong tool and no session is no tool at all.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/webdav/tokens").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/webdav/tokens") {
                contentType(ContentType.Application.Json)
                setBody("""{"label":"sneaky"}""")
            }.status,
        )
    }

    @Test
    fun repeatedFailuresDoNotConsumeTheLoginBudget() = testApplication {
        setup()
        repeat(12) {
            val r = client.get("/dav/") {
                header(HttpHeaders.Authorization, basic("user1", "wrong-token"))
            }
            assertEquals(HttpStatusCode.Unauthorized, r.status)
        }
        // A mount re-sends credentials on every request, so a client retry loop
        // with a stale password is routine, not an attack. Sharing the login
        // budget would let the mount lock its own owner out of the account.
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status, "a mount retry loop must not lock the account")
    }

    @Test
    fun aBlankOrOverlongLabelIsRefused() = testApplication {
        setup()
        assertEquals(HttpStatusCode.BadRequest, createToken("   ").status)
        assertEquals(HttpStatusCode.BadRequest, createToken("x".repeat(65)).status)
    }

    @Test
    fun theTokenTableStoresTheOwnerSoRevocationIsScoped() = testApplication {
        setup()
        newToken("mine")
        val owners = transaction {
            WebdavTokensTable.selectAll().map { it[WebdavTokensTable.user] }
        }
        val me = transaction { UsersTable.selectAll().single()[UsersTable.id] }
        assertEquals(listOf(me), owners, "the token must be bound to the creating account")
    }
}
