package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.ContentsResponse
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.*

class ThumbnailTest {
    private val storageDir = Files.createTempDirectory("bz-thumb").toString()
    private fun cfg() = AppConfig(0, "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30)
    private var auth = ""
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        val storage = LocalStorageProvider(java.nio.file.Path.of(c.storageDir))
        application { module(c, storage) }
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        val login = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.setupSecondUser(): String {
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"user2","password":"password123"}""") }
        val login = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"user2","password":"password123"}""") }
        return "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    private fun sha256hex(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Chunked upload; returns the created file id. */
    private suspend fun ApplicationTestBuilder.upload(body: ByteArray, name: String, mime: String = "application/octet-stream"): String {
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"mimeType":"$mime","sha256":"${sha256hex(body)}"}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        if (!ir.instantUpload) {
            val put = client.put("/api/uploads/${ir.uploadId}/chunks/0") {
                header(HttpHeaders.Authorization, auth); setBody(body)
            }
            assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
            val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
            assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
            return json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
        }
        return ir.file!!.id
    }

    /** Instant (dedup) upload of already-stored content; returns the init response payload. */
    private suspend fun ApplicationTestBuilder.instantUpload(body: ByteArray, name: String) =
        json.decodeFromString<UploadInitResponse>(
            client.post("/api/uploads/init") {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"$name","size":${body.size},"mimeType":"image/jpeg","sha256":"${sha256hex(body)}"}""")
            }.bodyAsText(),
        )

    private fun fakeJpeg(size: Int = 1024) = ByteArray(size) { it.toByte() } + byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun putThenGetReturnsSameJpegWithHeaders() = testApplication {
        setup()
        val body = "fake image content".encodeToByteArray()
        val fileId = upload(body, "pic.jpg", mime = "image/jpeg")
        val thumb = fakeJpeg()
        val put = client.put("/api/files/$fileId/thumbnail") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Image.JPEG); setBody(thumb)
        }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        val get = client.get("/api/files/$fileId/thumbnail") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, get.status, get.bodyAsText())
        assertEquals("image/jpeg", get.contentType()?.withoutParameters()?.toString())
        assertEquals(thumb.toList(), get.bodyAsBytes().toList())
        assertEquals(sha256hex(body), get.headers[HttpHeaders.ETag])
        assertTrue(get.headers[HttpHeaders.CacheControl]?.contains("immutable") == true, get.headers.toString())
    }

    @Test
    fun getWithoutThumbnail404() = testApplication {
        setup()
        val fileId = upload("no thumb yet".encodeToByteArray(), "raw.txt")
        val get = client.get("/api/files/$fileId/thumbnail") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NotFound, get.status)
    }

    @Test
    fun putOversizedBody400() = testApplication {
        setup()
        val fileId = upload("big".encodeToByteArray(), "big.jpg", mime = "image/jpeg")
        val tooBig = ByteArray(512 * 1024 + 1) { 7 }
        val put = client.put("/api/files/$fileId/thumbnail") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Image.JPEG); setBody(tooBig)
        }
        assertEquals(HttpStatusCode.BadRequest, put.status, put.bodyAsText())
    }

    @Test
    fun putOtherUsersFile404() = testApplication {
        setup()
        val fileId = upload("private".encodeToByteArray(), "private.jpg", mime = "image/jpeg")
        val otherAuth = setupSecondUser()
        val put = client.put("/api/files/$fileId/thumbnail") {
            header(HttpHeaders.Authorization, otherAuth); contentType(ContentType.Image.JPEG); setBody(fakeJpeg())
        }
        assertEquals(HttpStatusCode.NotFound, put.status)
    }

    @Test
    fun sameContentRowsAllFlagged() = testApplication {
        setup()
        val body = "dedup me".encodeToByteArray()
        val id1 = upload(body, "one.jpg", mime = "image/jpeg")
        val instant = instantUpload(body, "two.jpg")
        assertTrue(instant.instantUpload)
        val id2 = instant.file!!.id
        val put = client.put("/api/files/$id1/thumbnail") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Image.JPEG); setBody(fakeJpeg())
        }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        val contents = client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, contents.status, contents.bodyAsText())
        val files = json.decodeFromString<ContentsResponse>(contents.bodyAsText()).files
        assertEquals(listOf(true, true), files.sortedBy { it.name }.map { it.hasThumbnail })
        assertNotNull(id2)
    }

    @Test
    fun instantUploadInitializesFlagFromExistingThumb() = testApplication {
        setup()
        val body = "thumb exists".encodeToByteArray()
        val id1 = upload(body, "first.jpg", mime = "image/jpeg")
        client.put("/api/files/$id1/thumbnail") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Image.JPEG); setBody(fakeJpeg())
        }
        val instant = instantUpload(body, "second.jpg")
        assertTrue(instant.instantUpload)
        assertEquals(true, instant.file!!.hasThumbnail)
    }

    @Test
    fun unauthenticatedPut401() = testApplication {
        setup()
        val fileId = upload("anon".encodeToByteArray(), "anon.jpg", mime = "image/jpeg")
        val put = client.put("/api/files/$fileId/thumbnail") { contentType(ContentType.Image.JPEG); setBody(fakeJpeg()) }
        assertEquals(HttpStatusCode.Unauthorized, put.status)
        val get = client.get("/api/files/$fileId/thumbnail")
        assertEquals(HttpStatusCode.Unauthorized, get.status)
    }

    @Test
    // These two tests call setup() themselves: the JPEG bytes from ImageIO make
    // them sensitive to class-level storageDir reuse (see getWithoutThumbnail404).
    fun getStill404ForUndecodableImage() = testApplication {
        setup()
        val fileId = upload("not an image".encodeToByteArray(), "fake.png", mime = "image/png")
        val get = client.get("/api/files/$fileId/thumbnail") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NotFound, get.status)
    }

    @Test
    fun getGeneratesServerSideThumbnailForDecodableImage() = testApplication {
        setup()
        val g = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
        g.setRGB(0, 0, 0xFFFF0000.toInt())
        val jpg = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(g, "jpg", it) }.toByteArray()
        val fileId = upload(jpg, "real.jpg", mime = "image/jpeg")
        val get = client.get("/api/files/$fileId/thumbnail") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, get.status, get.bodyAsText())
        assertEquals("image/jpeg", get.contentType()?.withoutParameters()?.toString())
        assertTrue(get.bodyAsBytes().size > 0)
        val contents = client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }
        val files = json.decodeFromString<ContentsResponse>(contents.bodyAsText()).files
        assertEquals(listOf(true), files.map { it.hasThumbnail })
    }
}
