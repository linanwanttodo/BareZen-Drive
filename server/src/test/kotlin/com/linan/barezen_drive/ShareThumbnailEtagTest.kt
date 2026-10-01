package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.ShareDto
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The public share thumbnail route emitted an ETag that could not do its job.
 *
 * Three separate faults lined up on one response:
 *
 *  1. The tag was the *source file's* sha256 rather than the cover's bytes. The
 *     cover slot is replaceable, so a client that fetched a bad cover and then
 *     saw it corrected would be handed an identical tag and keep the bad one.
 *  2. It was unquoted. RFC 9110 §8.8.3 defines an entity-tag as
 *     `[ weak ] opaque-tag` with opaque-tag wrapped in DQUOTEs, so an unquoted
 *     value is not a tag at all and a strict client cannot compare it.
 *  3. `If-None-Match` was ignored, so the tag was decorative: every repeat fetch
 *     re-sent the whole image.
 *
 * And the Cache-Control said `immutable` for a year, which turned the first two
 * faults from "no revalidation" into "no revalidation, ever".
 *
 * The sibling route at `files/ThumbnailRoutes.kt` got all of this right and says
 * why in a comment; this route is the one that was missed.
 */
class ShareThumbnailEtagTest {
    private val storageDir = Files.createTempDirectory("bz-shareetag").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""

    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
        registrationOpen = true,
    )

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""")
            .find(
                client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"user1","password":"password123"}""")
                }.bodyAsText(),
            )!!.groupValues[1]
    }

    private fun sha256hex(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** A real JPEG, because the cover only exists if the bytes actually decode. */
    private fun jpeg(): ByteArray {
        val g = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 64) for (y in 0 until 64) g.setRGB(x, y, (x * 4) shl 24 or (y * 4) shl 8 or 0x40)
        return ByteArrayOutputStream().also { javax.imageio.ImageIO.write(g, "jpg", it) }.toByteArray()
    }

    private suspend fun ApplicationTestBuilder.upload(body: ByteArray, name: String, mime: String): String {
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"mimeType":"$mime","sha256":"${sha256hex(body)}"}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        if (!ir.instantUpload) {
            assertEquals(
                HttpStatusCode.NoContent,
                client.put("/api/uploads/${ir.uploadId}/chunks/0") {
                    header(HttpHeaders.Authorization, auth); setBody(body)
                }.status,
            )
            val done = client.post("/api/uploads/${ir.uploadId}/complete") {
                header(HttpHeaders.Authorization, auth)
            }
            assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
            return json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
        }
        return ir.file!!.id
    }

    /**
     * Upload a cover explicitly.
     *
     * `complete()` only *schedules* cover generation on a background coroutine
     * (`bgScope.launch`), so a cover does not exist the moment the upload
     * returns. Waiting for it would make this test a sleep-and-hope; putting one
     * is deterministic and is the same path a client uses to correct a bad cover.
     */
    private suspend fun ApplicationTestBuilder.putCover(fileId: String, bytes: ByteArray) {
        val r = client.put("/api/files/$fileId/thumbnail") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Image.JPEG)
            setBody(bytes)
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.share(fileId: String): String {
        val res = client.post("/api/shares") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"fileId":"$fileId"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        return json.decodeFromString<ShareDto>(res.bodyAsText()).url.removePrefix("/s/")
    }

    @Test
    fun theShareThumbnailEtagIsQuotedAndHonoured() = testApplication {
        setup()
        val body = jpeg()
        val fileId = upload(body, "pic.jpg", "image/jpeg")
        putCover(fileId, body)
        val token = share(fileId)
        val url = "/api/public/shares/$token/files/$fileId/thumbnail"

        val first = client.get(url)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val etag = first.headers[HttpHeaders.ETag]
        assertNotEquals(null, etag, "a cover must carry an ETag")
        assertTrue(
            etag!!.startsWith("\"") && etag.endsWith("\""),
            "RFC 9110 §8.8.3 requires DQUOTEs around an entity-tag, got: $etag",
        )
        assertTrue(first.bodyAsBytes().isNotEmpty(), "the first fetch must carry the image")

        val second = client.get(url) { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, second.status, "If-None-Match must be honoured")
        assertEquals(0, second.bodyAsBytes().size, "a 304 must not carry a body")
    }

    @Test
    fun aStaleEtagStillServesTheImage() = testApplication {
        setup()
        val body = jpeg()
        val fileId = upload(body, "pic.jpg", "image/jpeg")
        putCover(fileId, body)
        val token = share(fileId)
        val url = "/api/public/shares/$token/files/$fileId/thumbnail"

        val r = client.get(url) { header(HttpHeaders.IfNoneMatch, "\"0000deadbeef\"") }
        assertEquals(HttpStatusCode.OK, r.status, "a stale validator must get the bytes, not a 304")
        assertTrue(r.bodyAsBytes().isNotEmpty())
    }

    @Test
    fun theCoverCanBeRevalidatedRatherThanPinnedForAYear() = testApplication {
        setup()
        val body = jpeg()
        val fileId = upload(body, "pic.jpg", "image/jpeg")
        putCover(fileId, body)
        val token = share(fileId)
        val r = client.get("/api/public/shares/$token/files/$fileId/thumbnail")
        val cache = r.headers[HttpHeaders.CacheControl].orEmpty()
        // A replaceable cover behind `immutable` is the combination that let a
        // corrected cover stay invisible for a year.
        assertTrue(
            "immutable" !in cache,
            "a cover can be replaced, so it must not be served immutable: $cache",
        )
    }
}
