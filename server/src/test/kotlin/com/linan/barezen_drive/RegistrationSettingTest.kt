package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.*

class RegistrationSettingTest {
    private val storageDir = Files.createTempDirectory("bz-reg").toString()

    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
        updateRepoUrl = "https://example.invalid/not-github",
    )

    private var auth = ""

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"owner1","password":"password123"}""") }
        val login = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"owner1","password":"password123"}""") }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    @Test
    fun registrationDefaultsToOpenAndCanBeToggled() = testApplication {
        setup()

        // Default: open (a fresh private drive needs its first account).
        val before = client.get("/api/settings/registration")
        assertEquals(HttpStatusCode.OK, before.status, before.bodyAsText())
        assertTrue("""{"open":true}""" == before.bodyAsText() || before.bodyAsText().contains("\"open\":true"), before.bodyAsText())

        // Unauthenticated toggle is rejected.
        val anon = client.patch("/api/settings/registration") {
            contentType(ContentType.Application.Json); setBody("""{"open":false}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, anon.status)

        // Owner closes registration; further sign-ups are refused with 403.
        val close = client.patch("/api/settings/registration") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json); setBody("""{"open":false}""")
        }
        assertEquals(HttpStatusCode.OK, close.status, close.bodyAsText())
        assertTrue(close.bodyAsText().contains("\"open\":false"), close.bodyAsText())

        val refused = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody("""{"username":"intruder","password":"password123"}""") }
        assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
        assertTrue(refused.bodyAsText().contains("REGISTRATION_DISABLED"), refused.bodyAsText())

        // Reopening accepts sign-ups again.
        val reopen = client.patch("/api/settings/registration") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json); setBody("""{"open":true}""")
        }
        assertEquals(HttpStatusCode.OK, reopen.status)
        val after = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody("""{"username":"second","password":"password123"}""") }
        assertEquals(HttpStatusCode.Created, after.status, after.bodyAsText())
    }
}
