package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The auth limiter keys on the caller's address, and `X-Forwarded-For` is the
 * only source of that address behind a reverse proxy - which makes the header
 * the attacker's to write unless the server knows who is allowed to write it.
 *
 * These tests drive the limiter through the header from a test client whose
 * socket peer is loopback, so the trusted range is either `127.0.0.0/8` (the
 * test client is then a proxy and is believed) or something else (it is then an
 * ordinary client and its header is ignored). TRUST_PROXY is exercised both ways
 * as well: it cannot make a non-proxy peer credible.
 */
class TrustedProxyTest {

    private fun cfg(trustProxy: Boolean, cidrs: List<String>) = AppConfig(
        port = 0,
        jdbcUrl = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        dbUser = "sa", dbPassword = "",
        jwtSecret = "test-secret-0123456789abcdef0123456789abcdef",
        storageDir = Files.createTempDirectory("bz-proxy").toString(),
        maxFileSize = 1L shl 30,
        trustProxy = trustProxy,
        trustedProxyCidrs = cidrs,
        // The register limiter (5 per address) is the subject here, so the
        // account it creates must not be gated by the registration switch.
        registrationOpen = true,
    )

    private suspend fun ApplicationTestBuilder.setup(trustProxy: Boolean, cidrs: List<String>) {
        val c = cfg(trustProxy, cidrs)
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
    }

    private suspend fun ApplicationTestBuilder.register(ip: String, user: String) =
        client.post("/api/auth/register") {
            header("X-Forwarded-For", ip)
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$user","password":"password123"}""")
        }

    /**
     * TRUST_PROXY on its own used to mean "believe the header from anybody",
     * which hands the limiter to whoever can reach the port: six registers from
     * six forged addresses all get their own budget. A peer that is not a
     * configured proxy must not be believed, whatever the legacy flag says.
     */
    @Test
    fun forgedHeaderCannotBypassTheRegisterLimitForANonProxyPeer() = testApplication {
        setup(trustProxy = true, cidrs = listOf("203.0.113.0/24"))
        repeat(5) { i ->
            val ok = register("198.51.100.$i", "spoofer$i")
            assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
        }
        // The sixth claims a sixth forged address, but it is the same socket:
        // the header is not evidence of anything here.
        val blocked = register("198.51.100.99", "spoofer9")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, blocked.bodyAsText())
    }

    /**
     * The configured range is the trust boundary: a peer inside it may speak for
     * its client, and then each distinct client address gets its own budget.
     */
    @Test
    fun aConfiguredProxyIsBelievedAboutItsClients() = testApplication {
        setup(trustProxy = false, cidrs = listOf("127.0.0.0/8"))
        // The register limiter allows five per address; six distinct clients must
        // therefore all be served from six separate windows.
        repeat(6) { i ->
            val ok = register("203.0.113.$i", "guest$i")
            assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
        }
    }

    /**
     * A caller that wants to hide behind a proxy address writes it on the LEFT
     * of the chain. Only the right-most non-proxy hop identifies the client, so
     * the six requests below are the same client (the 6th is over the limit)
     * while a different right-most hop is a different client.
     */
    @Test
    fun aForgedProxyPrefixDoesNotMintANewBudget() = testApplication {
        setup(trustProxy = false, cidrs = listOf("127.0.0.0/8"))
        repeat(5) {
            val ok = register("10.0.0.1, 198.51.100.5", "forged$it")
            assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
        }
        val blocked = register("10.0.0.1, 198.51.100.5", "forged9")
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, blocked.bodyAsText())
        val other = register("10.0.0.1, 198.51.100.6", "honest")
        assertEquals(HttpStatusCode.Created, other.status, other.bodyAsText())
    }
}
