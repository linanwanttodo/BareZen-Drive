package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/**
 * The auth endpoints are the only unauthenticated surface that touches the DB,
 * so each of them carries an IP-keyed fixed-window limiter. These tests pin the
 * contract: the buckets are keyed by client IP (X-Forwarded-For when a trusted
 * proxy sets it), a caller over the limit gets 429 RATE_LIMITED, and a different
 * caller is unaffected.
 *
 * Throttle.reset() runs on every module() call, so each test starts with empty
 * windows and can spend its own budget from scratch.
 */
class RateLimitTest {

    private fun cfg(trustProxy: Boolean = true) = AppConfig(
        port = 0,
        jdbcUrl = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        dbUser = "sa", dbPassword = "",
        jwtSecret = "test-secret-0123456789abcdef0123456789abcdef",
        storageDir = Files.createTempDirectory("bz-ratelimit").toString(),
        maxFileSize = 1L shl 30,
        // These tests drive the limiter through X-Forwarded-For, which is the
        // trusted-proxy mode; TRUST_PROXY=false is covered by its own case below.
        trustProxy = trustProxy,
    )

    private fun ApplicationTestBuilder.setup(trustProxy: Boolean = true) {
        val c = cfg(trustProxy)
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
    }

    private suspend fun ApplicationTestBuilder.register(ip: String, user: String) =
        client.post("/api/auth/register") {
            header("X-Forwarded-For", ip)
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$user","password":"password123"}""")
        }

    private suspend fun ApplicationTestBuilder.login(ip: String, user: String, password: String) =
        client.post("/api/auth/login") {
            header("X-Forwarded-For", ip)
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$user","password":"$password"}""")
        }

    private suspend fun ApplicationTestBuilder.refresh(ip: String) =
        client.post("/api/auth/refresh") {
            header("X-Forwarded-For", ip)
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"${UUID.randomUUID()}"}""")
        }

    @Test
    fun registerBucketAllowsFivePerIpThenBlocks() = testApplication {
        setup()
        val ip = "203.0.113.7"
        repeat(5) { i ->
            val ok = register(ip, "user$i")
            assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
        }
        val blocked = register(ip, "user9")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, blocked.bodyAsText())
        assertTrue(blocked.bodyAsText().contains("RATE_LIMITED"), blocked.bodyAsText())

        // The window is keyed by client IP, not global: a second caller is served.
        val other = register("203.0.113.8", "voter1")
        assertEquals(HttpStatusCode.Created, other.status, other.bodyAsText())
    }

    @Test
    fun loginBucketAllowsTenPerIpThenBlocks() = testApplication {
        setup()
        val ip = "198.51.100.4"
        register("198.51.100.5", "bob")
        // Ten failed attempts still count against the budget: throttling must not
        // depend on the credentials being right.
        repeat(10) {
            val bad = login(ip, "bob", "wrongpass1")
            assertEquals(HttpStatusCode.Unauthorized, bad.status, bad.bodyAsText())
        }
        val blocked = login(ip, "bob", "password123")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, blocked.bodyAsText())
        assertTrue(blocked.bodyAsText().contains("RATE_LIMITED"), blocked.bodyAsText())

        // Same credentials from another IP still work.
        val other = login("198.51.100.6", "bob", "password123")
        assertEquals(HttpStatusCode.OK, other.status, other.bodyAsText())
    }

    @Test
    fun refreshBucketAllowsThirtyPerIpThenBlocks() = testApplication {
        setup()
        val ip = "192.0.2.30"
        repeat(30) {
            val bad = refresh(ip)
            assertEquals(HttpStatusCode.Unauthorized, bad.status, bad.bodyAsText())
        }
        val blocked = refresh(ip)
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, blocked.bodyAsText())
        assertTrue(blocked.bodyAsText().contains("RATE_LIMITED"), blocked.bodyAsText())

        val other = refresh("192.0.2.31")
        assertEquals(HttpStatusCode.Unauthorized, other.status, other.bodyAsText())
    }

    /**
     * Without a trusted proxy the header is just another client-controlled
     * string: rotating it must not create a fresh window per request, or the
     * limiter would be decoration.
     */
    @Test
    fun forgedForwardedHeaderCannotBypassWithoutTrustedProxy() = testApplication {
        setup(trustProxy = false)
        repeat(5) { i ->
            val ok = register("203.0.113.$i", "spoofer$i")
            assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
        }
        // A sixth distinct forged IP is the same caller as far as the socket
        // agrees, so it lands in the window that is already full.
        val blocked = register("198.18.0.1", "spoofer9")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, blocked.bodyAsText())
    }

    /**
     * The per-account budget is keyed by username, so an attacker who rotates
     * addresses still runs out of guesses against one account.
     */
    @Test
    fun accountBudgetSurvivesIpRotation() = testApplication {
        setup()
        register("198.51.100.20", "target")
        repeat(20) { i ->
            val bad = login("198.51.100.$i", "target", "wrongpass1")
            assertEquals(HttpStatusCode.Unauthorized, bad.status, bad.bodyAsText())
        }
        // Every one of those came from its own IP bucket, which is still nearly
        // empty; the account window is what stops the next attempt.
        val blocked = login("203.0.113.99", "target", "password123")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, blocked.bodyAsText())
        assertTrue(blocked.bodyAsText().contains("RATE_LIMITED"), blocked.bodyAsText())
    }
}
