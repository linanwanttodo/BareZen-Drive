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
import kotlin.test.assertTrue

/**
 * `/api/updates/download/{tag}/{file}` is public by design (an updater has no
 * session yet) and it streams a release asset straight from github.com. Without
 * a limiter that turns the instance into an unmetered relay for a third party:
 * anyone who can reach the port can have the server fetch and forward packages
 * at its own uplink, repeatedly.
 *
 * The requests below carry a path the route rejects, so the test exercises the
 * limiter without pulling anything from github: the limiter has to run first,
 * otherwise a rejected path would be a free way to probe it.
 */
class UpdateProxyThrottleTest {
    /** Must match the limit the route installs. */
    private val limit = 30

    private fun cfg() = AppConfig(
        port = 0,
        jdbcUrl = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        dbUser = "sa", dbPassword = "",
        jwtSecret = "test-secret-0123456789abcdef0123456789abcdef",
        storageDir = Files.createTempDirectory("bz-proxy-update").toString(),
        maxFileSize = 1L shl 30,
        trustProxy = false,
        trustedProxyCidrs = listOf("127.0.0.0/8"),
        registrationOpen = true,
    )

    @Test
    fun theDownloadProxyIsRateLimitedPerAddress() = testApplication {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }

        // A path the route rejects: 400 while there is budget left, and no
        // upstream fetch happens at any point. `~` survives URL encoding and is
        // not in the accepted file charset.
        suspend fun download() = client.get("/api/updates/download/v1.0.0/bad~name.apk")

        repeat(limit) {
            val res = download()
            assertEquals(HttpStatusCode.BadRequest, res.status, res.bodyAsText())
        }
        val blocked = download()
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, blocked.bodyAsText())
        assertTrue(blocked.bodyAsText().contains("RATE_LIMITED"), blocked.bodyAsText())
    }
}
