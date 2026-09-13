package com.linan.barezen_drive

import com.linan.barezen_drive.auth.LinkService
import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.FileLinkResponse
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

class LinkTest {
    private val storageDir = Files.createTempDirectory("bz-link").toString()
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

    private fun sha256hex(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private suspend fun ApplicationTestBuilder.uploadAndGetId(body: ByteArray, name: String): String {
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"sha256":"${sha256hex(body)}"}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        assertFalse(ir.instantUpload)
        val put = client.put("/api/uploads/${ir.uploadId}/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(body) }
        assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
        val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
    }

    @Test
    fun linkGrantsAnonymousContentAccess() = testApplication {
        setup()
        val body = "signed link body".encodeToByteArray()
        val fileId = uploadAndGetId(body, "linked.txt")
        val link = client.get("/api/files/$fileId/link") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, link.status, link.bodyAsText())
        val lr = json.decodeFromString<FileLinkResponse>(link.bodyAsText())
        assertTrue(lr.url.contains("sig="), lr.url)
        assertTrue(lr.url.contains("exp="), lr.url)
        // No Authorization header at all - the signature is the capability.
        val res = client.get(lr.url)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals(body.decodeToString(), res.bodyAsText())
    }

    @Test
    fun linkRequiresAuth() = testApplication {
        setup()
        val body = "private".encodeToByteArray()
        val fileId = uploadAndGetId(body, "private.txt")
        val link = client.get("/api/files/$fileId/link")
        assertEquals(HttpStatusCode.Unauthorized, link.status)
    }

    @Test
    fun expiredSignature401() = testApplication {
        setup()
        val body = "expired".encodeToByteArray()
        val fileId = uploadAndGetId(body, "expired.txt")
        val exp = System.currentTimeMillis() / 1000 - 100
        val url = "/api/files/$fileId/content?exp=$exp&sig=${LinkService.sign(UUID.fromString(fileId), exp)}"
        val res = client.get(url)
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun tamperedSignature401() = testApplication {
        setup()
        val body = "tampered".encodeToByteArray()
        val fileId = uploadAndGetId(body, "tampered.txt")
        val exp = System.currentTimeMillis() / 1000 + 300
        val good = LinkService.sign(UUID.fromString(fileId), exp)
        val bad = good.dropLast(2) + if (good.takeLast(2) == "aa") "bb" else "aa"
        val res = client.get("/api/files/$fileId/content?exp=$exp&sig=$bad")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals(64, good.length)
    }

    @Test
    fun signatureBoundToSpecificFile() = testApplication {
        setup()
        val fileId = uploadAndGetId("target".encodeToByteArray(), "target.txt")
        // A signature minted for a different file id must not open this file.
        val exp = System.currentTimeMillis() / 1000 + 300
        val otherSig = LinkService.sign(UUID.randomUUID(), exp)
        val res = client.get("/api/files/$fileId/content?exp=$exp&sig=$otherSig")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun linkStillWorksWithBearerAndTtlIsClamped() = testApplication {
        setup()
        val fileId = uploadAndGetId("bearer".encodeToByteArray(), "bearer.txt")
        // TTL clamped to [30, 3600] regardless of the requested value.
        val link = client.get("/api/files/$fileId/link?ttl=999999") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, link.status, link.bodyAsText())
        val lr = json.decodeFromString<FileLinkResponse>(link.bodyAsText())
        val exp = Regex("exp=(\\d+)").find(lr.url)!!.groupValues[1].toLong()
        val delta = exp - System.currentTimeMillis() / 1000
        assertTrue(delta in 29..3601, "ttl not clamped: $delta")
        val res = client.get(lr.url) { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status)
    }
}
