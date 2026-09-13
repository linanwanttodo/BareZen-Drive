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
 * Static hosting rules for the compiled web client: asset lookup, SPA fallback
 * and the traversal guard.
 *
 * The guard originally rejected any decoded path containing a bare ".." as a
 * substring, which also discarded legitimate asset names such as
 * "chart..final.png": the request silently got index.html with a no-cache
 * header instead of the asset, so the browser never saw it (and, for a
 * hash-named build output, could keep serving a stale entry).
 */
class StaticWebTest {

    /** Fixture served by the classpath lookup; see src/test/resources/web/. */
    private val dottedAsset = "chart..final.png"

    private fun cfg() = AppConfig(
        port = 0,
        jdbcUrl = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        dbUser = "sa", dbPassword = "",
        jwtSecret = "test-secret-0123456789abcdef0123456789abcdef",
        storageDir = Files.createTempDirectory("bz-web").toString(),
        maxFileSize = 1L shl 30,
    )

    private fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
    }

    private fun fixtureBytes(): ByteArray =
        requireNotNull(javaClass.classLoader.getResource("web/$dottedAsset")) {
            "test fixture web/$dottedAsset is missing from the test classpath"
        }.readBytes()

    @Test
    fun assetNameContainingDoubleDotIsServed() = testApplication {
        setup()
        val res = client.get("/$dottedAsset")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals("image/png", res.contentType()?.withoutParameters()?.toString())
        // The real payload, not the SPA entry page.
        assertEquals(fixtureBytes().toList(), res.bodyAsBytes().toList())
        // Hash-named build outputs are immutable; the SPA fallback is no-cache.
        assertEquals("public, max-age=31536000, immutable", res.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun encodedSingleDotSegmentIsRejected() = testApplication {
        setup()
        // %2e decodes to "."; a segment that IS "." must never reach the lookup.
        val res = client.get("/%2e/index.html")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("no-cache", res.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun encodedTraversalSegmentsNeverReachTheClasspathRoot() = testApplication {
        setup()
        // logback.xml sits at the classpath root, next to web/. Every one of these
        // shapes must fall back to the SPA entry page instead of leaking it.
        val attempts = listOf(
            "/%2e%2e/logback.xml",       // decoded segment == ".."
            "/..%5Clogback.xml",         // decoded segment contains a backslash
            "/..%2Flogback.xml",         // decoded segment contains a separator
            "/%2e%2e%2fsigv4/get-vanilla.req", // separator smuggled across segments
        )
        for (path in attempts) {
            val res = client.get(path)
            val body = res.bodyAsText()
            assertEquals(HttpStatusCode.OK, res.status, "$path -> ${res.status}")
            assertEquals(ContentType.Text.Html, res.contentType()?.withoutParameters(), "$path leaks a non-HTML asset")
            assertFalse(body.contains("<configuration"), "$path served logback.xml")
            assertFalse(body.contains("AWS4-HMAC-SHA256"), "$path served a sigv4 fixture")
        }
    }

    @Test
    fun unknownClientRouteStillGetsTheSpaEntryPage() = testApplication {
        setup()
        val res = client.get("/albums/2026/09")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(ContentType.Text.Html, res.contentType()?.withoutParameters())
        assertTrue(res.bodyAsText().contains("<html", ignoreCase = true), res.bodyAsText())
    }

    @Test
    fun reservedPrefixesStillAnswer404() = testApplication {
        setup()
        // Unmatched /api/** must not fall through to the SPA page.
        val res = client.get("/api/definitely-not-a-route")
        assertEquals(HttpStatusCode.NotFound, res.status)
    }
}
