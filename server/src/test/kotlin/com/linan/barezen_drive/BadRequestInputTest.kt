package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

/**
 * Regression coverage for the v0.0.1 final-review hardening: malformed path
 * UUIDs, malformed chunk indexes and malformed JSON bodies must map to 400
 * (not 500).
 */
class BadRequestInputTest {
    private val storageDir = java.nio.file.Files.createTempDirectory("bz-bad").toString()
    private fun cfg() = AppConfig(0, "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30)

    private var auth = ""

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        val storage = LocalStorageProvider(java.nio.file.Path.of(c.storageDir))
        application { module(c, storage) }
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        val login = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    @Test
    fun malformedUuidPathParamReturns400() = testApplication {
        setup()
        val contents = client.get("/api/folders/not-a-uuid/contents") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.BadRequest, contents.status)
        assertTrue(contents.bodyAsText().contains("VALIDATION_ERROR"))
        val rename = client.patch("/api/folders/zzz") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"x"}""") }
        assertEquals(HttpStatusCode.BadRequest, rename.status)
        val delFolder = client.delete("/api/folders/zzz") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.BadRequest, delFolder.status)
        val patchFile = client.patch("/api/files/zzz") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"x"}""") }
        assertEquals(HttpStatusCode.BadRequest, patchFile.status)
        val delFile = client.delete("/api/files/zzz") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.BadRequest, delFile.status)
        val download = client.get("/api/files/zzz/content") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.BadRequest, download.status)
    }

    @Test
    fun malformedChunkSessionAndIndexReturn400() = testApplication {
        setup()
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"n.bin","size":16,"chunkSize":${1024L * 1024}}""") }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val uploadId = Regex(""""uploadId":"([^"]+)"""").find(init.bodyAsText())!!.groupValues[1]
        val badSession = client.put("/api/uploads/zzz/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(ByteArray(16)) }
        assertEquals(HttpStatusCode.BadRequest, badSession.status)
        val badIndex = client.put("/api/uploads/$uploadId/chunks/abc") { header(HttpHeaders.Authorization, auth); setBody(ByteArray(16)) }
        assertEquals(HttpStatusCode.BadRequest, badIndex.status)
        assertTrue(badIndex.bodyAsText().contains("CHUNK_INVALID"))
    }

    @Test
    fun malformedJsonBodyReturns400() = testApplication {
        setup()
        val login = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username": """) }
        assertEquals(HttpStatusCode.BadRequest, login.status)
        assertTrue(login.bodyAsText().contains("VALIDATION_ERROR"))
    }
}
