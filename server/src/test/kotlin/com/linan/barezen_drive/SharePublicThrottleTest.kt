package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.ShareDto
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.files.SHARE_PUBLIC_BODY_BUDGET
import com.linan.barezen_drive.files.SHARE_PUBLIC_META_BUDGET
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
 * `/api/public/shares/{token}/…` is the only unauthenticated surface that
 * spends a database round trip *and* server bandwidth: every request resolves
 * the token hash, and `…/files/{fid}/content` streams the blob back. It carried
 * no limit at all, so one anonymous client could spend the connection pool on
 * token lookups or pull the same multi-gigabyte file in a loop.
 *
 * The budget is keyed by (client address, share token) rather than by address
 * alone: several people behind one household or corporate NAT share a single
 * address, and a per-address window would let one visitor's video playback
 * lock out everyone else on the same link. The token is the thing the caller
 * actually presents, so it is part of the key. Address alone would not bound
 * the guessing case either - every guess is a different token - which is why
 * the number is generous enough for real browsing rather than tight.
 *
 * Throttle.reset() runs on every module() call, so each test starts with empty
 * windows and spends its own budget.
 */
class SharePublicThrottleTest {
    private val storageDir = Files.createTempDirectory("bz-sharethrottle").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""

    /** Reads the budget out of the route so the test never hardcodes it. */
    private val metaBudget = SHARE_PUBLIC_META_BUDGET
    private val bodyBudget = SHARE_PUBLIC_BODY_BUDGET

    private suspend fun ApplicationTestBuilder.setup() {
        val c = AppConfig(
            0,
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "",
            "test-secret-0123456789abcdef0123456789abcdef",
            storageDir, 1L shl 30,
            // Registration is closed by default on a fresh instance; these tests
            // are not about that, so they open it explicitly.
            registrationOpen = true,
            trustProxy = true,
        )
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        auth = "Bearer " + Regex("\"accessToken\":\"([^\"]+)\"").find(login.bodyAsText())!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.uploadAndShare(name: String): Pair<String, String> {
        // Distinct content per name: identical bytes would dedup into an
        // instant upload and leave the chunk PUT without a session.
        val body = "public share body of $name".encodeToByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"sha256":"$sha","mimeType":"text/plain"}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        val put = client.put("/api/uploads/${ir.uploadId}/chunks/0") {
            header(HttpHeaders.Authorization, auth)
            setBody(body)
        }
        assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
        val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        val fileId = json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
        val created = client.post("/api/shares") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"fileId":"$fileId"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val token = json.decodeFromString<ShareDto>(created.bodyAsText()).url.removePrefix("/s/")
        return fileId to token
    }

    @Test
    fun publicMetadataStopsAfterItsBudgetAndIsKeyedByAddressAndToken() = testApplication {
        setup()
        val (_, tokenA) = uploadAndShare("a.txt")
        val (_, tokenB) = uploadAndShare("b.txt")
        val ip = "203.0.113.10"

        repeat(metaBudget) {
            val res = client.get("/api/public/shares/$tokenA") { header("X-Forwarded-For", ip) }
            assertEquals(HttpStatusCode.OK, res.status, "hit $it of the budget: ${res.bodyAsText()}")
        }
        val blocked = client.get("/api/public/shares/$tokenA") { header("X-Forwarded-For", ip) }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, blocked.bodyAsText())
        assertTrue(blocked.bodyAsText().contains("RATE_LIMITED"), blocked.bodyAsText())

        // A different link from the same visitor is a different budget: the key
        // carries the token, so one exhausted link cannot lock a browser out of
        // every other link the owner ever sent them.
        val other = client.get("/api/public/shares/$tokenB") { header("X-Forwarded-For", ip) }
        assertEquals(HttpStatusCode.OK, other.status, other.bodyAsText())

        // A different visitor on the same link is a different budget too.
        val visitor = client.get("/api/public/shares/$tokenA") { header("X-Forwarded-For", "198.51.100.20") }
        assertEquals(HttpStatusCode.OK, visitor.status, visitor.bodyAsText())
    }

    @Test
    fun bodyRouteHasItsOwnLargerBudget() = testApplication {
        setup()
        val (fileId, token) = uploadAndShare("c.txt")
        val ip = "203.0.113.20"

        // A player issuing many Range requests during one playback is the whole
        // reason the body route gets its own, larger window; the metadata budget
        // would cut a seek-heavy stream off mid-file.
        repeat(bodyBudget) {
            val res = client.get("/api/public/shares/$token/files/$fileId/content") {
                header("X-Forwarded-For", ip)
                header(HttpHeaders.Range, "bytes=0-3")
            }
            assertEquals(HttpStatusCode.PartialContent, res.status, "hit $it: ${res.bodyAsText()}")
        }
        val blocked = client.get("/api/public/shares/$token/files/$fileId/content") {
            header("X-Forwarded-For", ip)
            header(HttpHeaders.Range, "bytes=0-3")
        }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, blocked.bodyAsText())
        assertTrue(blocked.bodyAsText().contains("RATE_LIMITED"), blocked.bodyAsText())
    }

    @Test
    fun thumbnailAndContentsShareTheMetadataBudgetWithTheLandingPage() = testApplication {
        setup()
        val (fileId, token) = uploadAndShare("d.txt")
        val ip = "203.0.113.30"
        // Every public endpoint draws on the same per-link window, so an
        // attacker cannot spread the budget across four URLs.
        repeat(metaBudget) {
            val res = client.get("/api/public/shares/$token/files/$fileId/thumbnail") {
                header("X-Forwarded-For", ip)
            }
            // 404 is fine (no cover on this file); the point is that it is not 429.
            assertNotEquals(HttpStatusCode.TooManyRequests, res.status, "hit $it: ${res.bodyAsText()}")
        }
        val blocked = client.get("/api/public/shares/$token/contents") { header("X-Forwarded-For", ip) }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, blocked.bodyAsText())
    }

    @Test
    fun aMalformedTokenIsRefusedWithoutSpendingTheBudget() = testApplication {
        setup()
        val ip = "203.0.113.40"
        // The hex check runs before the limiter: a token that could never match
        // a row costs no query, so it must not cost a slot either.
        repeat(metaBudget + 20) {
            val res = client.get("/api/public/shares/not-hex") { header("X-Forwarded-For", ip) }
            assertEquals(HttpStatusCode.NotFound, res.status, res.bodyAsText())
        }
        val stillFine = client.get("/api/public/shares/${"cd".repeat(32)}") { header("X-Forwarded-For", ip) }
        assertEquals(HttpStatusCode.NotFound, stillFine.status)
    }

    @Test
    fun withoutATrustedProxyTheForwardedHeaderCannotMintFreshBudgets() = testApplication {
        val c = AppConfig(
            0,
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "",
            "test-secret-0123456789abcdef0123456789abcdef",
            Files.createTempDirectory("bz-sharethrottle-noproxy").toString(),
            1L shl 30,
            registrationOpen = true,
            trustProxy = false,
        )
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        // Every request rotates a different forged address; the limiter keys on
        // the socket peer, so they all land in the same window and it fills up.
        repeat(metaBudget) { i ->
            val res = client.get("/api/public/shares/${"ab".repeat(32)}") {
                header("X-Forwarded-For", "198.18.0.${i % 250 + 1}")
            }
            assertEquals(HttpStatusCode.NotFound, res.status, res.bodyAsText())
        }
        val blocked = client.get("/api/public/shares/${"ab".repeat(32)}") {
            header("X-Forwarded-For", "203.0.113.99")
        }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status, blocked.bodyAsText())
    }
}
