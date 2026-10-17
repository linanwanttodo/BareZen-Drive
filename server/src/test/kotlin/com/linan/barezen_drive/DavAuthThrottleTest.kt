package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.webdav.DAV_AUTH_MAX_PER_WINDOW
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The failed-authentication budget of a mount.
 *
 * A WebDAV client authenticates on *every* request, so an address that keeps
 * guessing has an unbounded number of tries and each one of them costs a
 * database lookup. The budget is what turns that into 20 guesses per window.
 * Counting alone is not a limit: this test pins the refusal, the per-address
 * keying, and the one property that makes the count safe to enforce at all -
 * a correct credential hands its unit back, so a healthy mount never spends its
 * own budget however long it stays mounted.
 */
class DavAuthThrottleTest {
    private val storageDir = Files.createTempDirectory("bz-davauth").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var token = ""

    private fun basic(user: String, pass: String) =
        "Basic " + "$user:$pass".toByteArray().encodeBase64()

    private suspend fun ApplicationTestBuilder.setup() {
        val c = AppConfig(
            0,
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
            registrationOpen = true,
            trustProxy = true,
            trustedProxyCidrs = listOf("127.0.0.0/8"),
        )
        application { module(c, LocalStorageProvider(Path.of(c.storageDir))) }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        val bearer = "Bearer " + Regex(""""accessToken":"([^"]+)"""")
            .find(client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"user1","password":"password123"}""")
            }.bodyAsText())!!.groupValues[1]
        token = (json.parseToJsonElement(
            client.post("/api/webdav/tokens") {
                header(HttpHeaders.Authorization, bearer)
                contentType(ContentType.Application.Json)
                setBody("""{"label":"mount","readOnly":false}""")
            }.bodyAsText(),
        ) as JsonObject)["plaintext"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.davProbe(
        pass: String,
        from: String,
    ): HttpResponse = client.get("/dav/") {
        header("X-Forwarded-For", from)
        header(HttpHeaders.Authorization, basic("user1", pass))
    }

    @Test
    fun aSpentAuthBudgetRefusesTheAddressItWasSpentFrom() = testApplication {
        setup()
        val ip = "203.0.113.20"

        // A whole window of successful requests, which is what a mounted drive
        // really does: none of them may leave a mark, because the next wrong
        // password from the same address is somebody else's guess, not this
        // mount's fault.
        repeat(DAV_AUTH_MAX_PER_WINDOW) { i ->
            assertEquals(
                HttpStatusCode.OK, davProbe(token, ip).status,
                "request ${i + 1} of an authenticated mount must not be limited",
            )
        }

        repeat(DAV_AUTH_MAX_PER_WINDOW) { i ->
            assertEquals(
                HttpStatusCode.Unauthorized, davProbe("guess-$i", ip).status,
                "attempt ${i + 1} is inside the budget and must be answered as a wrong password",
            )
        }

        val blocked = davProbe("guess-once-more", ip)
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, blocked.bodyAsText())
        assertTrue(blocked.bodyAsText().contains("RATE_LIMITED"), blocked.bodyAsText())

        // The budget belongs to the address, not to the server: another caller
        // is served, and with the wrong password answered the way a wrong
        // password is always answered.
        assertEquals(HttpStatusCode.Unauthorized, davProbe("guess", "203.0.113.21").status)

        // The refusal is a wall for the rest of the window, correct credential
        // included. That is the price of making the count mean something: an
        // attacker who kept guessing would otherwise get its lookups back as
        // soon as one of them happened to land.
        assertEquals(HttpStatusCode.TooManyRequests, davProbe(token, ip).status)
    }
}
