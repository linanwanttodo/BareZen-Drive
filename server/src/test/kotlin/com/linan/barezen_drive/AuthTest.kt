package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.core.dto.LoginResponse
import com.linan.barezen_drive.core.dto.RefreshRequest
import com.linan.barezen_drive.core.dto.RefreshResponse
import com.linan.barezen_drive.core.dto.UserDto
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class AuthTest {
    private fun testConfig() = AppConfig(
        port = 0,
        jdbcUrl = "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        dbUser = "sa", dbPassword = "",
        jwtSecret = "test-secret-0123456789abcdef0123456789abcdef",
        storageDir = java.nio.file.Files.createTempDirectory("bz-auth").toString(),
        maxFileSize = 1L shl 30,
    )

    private fun ApplicationTestBuilder.setup() {
        val cfg = testConfig()
        val storage = LocalStorageProvider(java.nio.file.Path.of(cfg.storageDir))
        application { module(cfg, storage) }
    }

    @Test
    fun registerLoginRefreshFlow() = testApplication {
        setup()
        val c = client

        val reg = c.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"linan","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.Created, reg.status)

        val dup = c.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"linan","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.Conflict, dup.status)
        assertTrue(dup.bodyAsText().contains("USERNAME_TAKEN"))

        val bad = c.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"x","password":"123"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)

        val login = c.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"linan","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val tokens = Json.decodeFromString<LoginResponse>(login.bodyAsText())

        val me = c.get("/api/me") {
            header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}")
        }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals("linan", Json.decodeFromString<UserDto>(me.bodyAsText()).username)

        val noAuth = c.get("/api/me")
        assertEquals(HttpStatusCode.Unauthorized, noAuth.status)
        assertTrue(noAuth.bodyAsText().contains("TOKEN_INVALID"))

        val refresh = c.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(RefreshRequest(tokens.refreshToken)))
        }
        assertEquals(HttpStatusCode.OK, refresh.status)
        val rotated = Json.decodeFromString<RefreshResponse>(refresh.bodyAsText())
        assertNotEquals(tokens.refreshToken, rotated.refreshToken)

        val refresh2 = c.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(RefreshRequest(tokens.refreshToken)))
        }
        assertEquals(HttpStatusCode.Unauthorized, refresh2.status) // rotation invalidates old token
    }

    @Test
    fun wrongPassword401() = testApplication {
        setup()
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"bob","password":"password123"}""")
        }
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"bob","password":"wrongpass1"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertTrue(res.bodyAsText().contains("INVALID_CREDENTIALS"))
    }
}
