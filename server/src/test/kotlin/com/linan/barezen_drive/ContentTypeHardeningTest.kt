package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
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
 * Uploaded files are served back from the same origin as the app, so a file
 * whose Content-Type the browser will execute is a stored-XSS hole: the attacker's
 * script runs on the netdisk origin and can read `bz_access_token` /
 * `bz_refresh_token` out of localStorage - the session of whoever opened the
 * link, owner included (the app itself opens share links in a new tab).
 *
 * Two layers, both pinned here:
 *  1. only media/document types that a browser renders inert are served
 *     inline; everything else is octet-stream + attachment, so the declared
 *     type can never become an execution context;
 *  2. every response carries `X-Content-Type-Options: nosniff`, which stops the
 *     browser from "fixing" a lying type back into something executable.
 */
class ContentTypeHardeningTest {
    private val storageDir = Files.createTempDirectory("bz-xss").toString()
    private fun cfg() = AppConfig(0, "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30)
    private var auth = ""
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        val login = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    private fun sha256hex(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private suspend fun ApplicationTestBuilder.upload(body: ByteArray, name: String, mime: String): String {
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"sha256":"${sha256hex(body)}","mimeType":"$mime"}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        if (!ir.instantUpload) {
            var i = 0
            while (i * ir.chunkSize < body.size) {
                val from = (i * ir.chunkSize).toInt()
                val to = minOf((i + 1) * ir.chunkSize, body.size.toLong()).toInt()
                val put = client.put("/api/uploads/${ir.uploadId}/chunks/$i") {
                    header(HttpHeaders.Authorization, auth)
                    setBody(body.copyOfRange(from, to))
                }
                assertEquals(HttpStatusCode.NoContent, put.status, "chunk $i: ${put.bodyAsText()}")
                i++
            }
            val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
            assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
            return json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
        }
        return json.decodeFromString<UploadCompleteResponse>(init.bodyAsText()).file.id
    }

    @Test
    fun htmlUploadIsServedAsAttachmentOctetStream() = testApplication {
        setup()
        val payload = "<html><body><script>fetch('/api/me').then(r=>r.text())</script></body></html>"
        val id = upload(payload.encodeToByteArray(), "evil.html", "text/html")

        val res = client.get("/api/files/$id/content") { header(HttpHeaders.Authorization, auth) }

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(
            ContentType.Application.OctetStream,
            res.contentType()?.withoutParameters(),
            "an executable type must never be served inline",
        )
        val disposition = res.headers[HttpHeaders.ContentDisposition].orEmpty()
        assertTrue(
            disposition.startsWith("attachment", ignoreCase = true),
            "expected an attachment disposition, got '$disposition'",
        )
        assertTrue(
            disposition.contains("filename*=UTF-8''evil.html") || disposition.contains("filename=\"evil.html\""),
            "the filename must survive the header, got '$disposition'",
        )
    }

    @Test
    fun svgUploadIsServedAsAttachment() = testApplication {
        setup()
        // SVG is an image type but a script container - the whitelist must not
        // wave it through just because it starts with image/.
        val id = upload("<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".encodeToByteArray(), "x.svg", "image/svg+xml")

        val res = client.get("/api/files/$id/content") { header(HttpHeaders.Authorization, auth) }

        assertEquals(
            ContentType.Application.OctetStream,
            res.contentType()?.withoutParameters(),
            "svg must not be served as an inline image type",
        )
        assertTrue(res.headers[HttpHeaders.ContentDisposition].orEmpty().startsWith("attachment", ignoreCase = true))
    }

    @Test
    fun pngUploadStaysInlineForTheImageViewer() = testApplication {
        setup()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val id = upload(png, "ok.png", "image/png")

        val res = client.get("/api/files/$id/content") { header(HttpHeaders.Authorization, auth) }

        // The fix must not break the one case the app depends on: images and
        // video have to keep rendering inline in the preview and <img> tags.
        assertEquals(ContentType.Image.PNG, res.contentType()?.withoutParameters())
        assertNull(res.headers[HttpHeaders.ContentDisposition], "inline media needs no attachment")
    }

    @Test
    fun everyResponseCarriesNosniff() = testApplication {
        setup()
        val res = client.get("/api/files/nope/content") { header(HttpHeaders.Authorization, auth) }
        assertEquals(
            "nosniff",
            res.headers["X-Content-Type-Options"],
            "nosniff must be global, not per-route",
        )
    }

    @Test
    fun oversizedJsonBodyIsRejectedBeforeAllocation() = testApplication {
        setup()
        // Unauthenticated surface: a few hundred MB of JSON must not reach the
        // heap just because the route reads it as JSON.
        val huge = buildString {
            append("""{"username":"x","password":"y","pad":"""")
            repeat(3_000_000) { append('a') }
            append("\"\"}")
        }
        val res = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(huge)
        }
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            res.status,
            "an oversized body must be refused up front, not after buffering it",
        )
    }
}
