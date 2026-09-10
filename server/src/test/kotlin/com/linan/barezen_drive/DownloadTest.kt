package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.RecentFilesResponse
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import com.linan.barezen_drive.db.FilesTable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class DownloadTest {
    private val storageDir = Files.createTempDirectory("bz-dl").toString()
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

    private suspend fun ApplicationTestBuilder.doUpload(body: ByteArray, name: String, sha: String? = null): HttpResponse {
        val initBody = buildString {
            append("""{"name":"$name","size":${body.size}""")
            if (sha != null) append(""","sha256":"$sha"""")
            append("}")
        }
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody(initBody) }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        if (ir.instantUpload) return init
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
        return client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
    }

    private suspend fun ApplicationTestBuilder.uploadAndGetId(body: ByteArray, name: String): String {
        val res = doUpload(body, name, sha = sha256hex(body))
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return json.decodeFromString<UploadCompleteResponse>(res.bodyAsText()).file.id
    }

    @Test
    fun fullBodyWithoutRange() = testApplication {
        setup()
        val body = "0123456789abcdefghij".encodeToByteArray()
        val fileId = uploadAndGetId(body, "full.txt")
        val res = client.get("/api/files/$fileId/content") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals(body.decodeToString(), res.bodyAsText())
        assertEquals("20", res.headers[HttpHeaders.ContentLength])
    }

    @Test
    fun rangeRequestReturns206() = testApplication {
        setup()
        val body = "0123456789abcdefghij".encodeToByteArray()
        val fileId = uploadAndGetId(body, "r.txt")
        // bytes=0-4 (inclusive) -> first 5 bytes
        val head = client.get("/api/files/$fileId/content") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.Range, "bytes=0-4") }
        assertEquals(HttpStatusCode.PartialContent, head.status, head.bodyAsText())
        assertEquals("01234", head.bodyAsText())
        assertEquals("bytes 0-4/20", head.headers[HttpHeaders.ContentRange])
        // bytes=-3 -> last 3 bytes of the 20-byte body
        val tail = client.get("/api/files/$fileId/content") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.Range, "bytes=-3") }
        assertEquals(HttpStatusCode.PartialContent, tail.status, tail.bodyAsText())
        assertEquals("hij", tail.bodyAsText())
        assertEquals("bytes 17-19/20", tail.headers[HttpHeaders.ContentRange])
        // bytes=5- -> suffix from index 5 to the end
        val mid = client.get("/api/files/$fileId/content") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.Range, "bytes=5-") }
        assertEquals(HttpStatusCode.PartialContent, mid.status, mid.bodyAsText())
        assertEquals("56789abcdefghij", mid.bodyAsText())
        assertEquals("bytes 5-19/20", mid.headers[HttpHeaders.ContentRange])
    }

    @Test
    fun unsatisfiableRangeReturns416() = testApplication {
        setup()
        val body = "0123456789abcdefghij".encodeToByteArray()
        val fileId = uploadAndGetId(body, "r416.txt")
        val res = client.get("/api/files/$fileId/content") { header(HttpHeaders.Authorization, auth); header(HttpHeaders.Range, "bytes=100-200") }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, res.status, res.bodyAsText())
        assertEquals("bytes */20", res.headers[HttpHeaders.ContentRange])
    }

    @Test
    fun missingFile404() = testApplication {
        setup()
        val res = client.get("/api/files/${UUID.randomUUID()}/content") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertTrue(res.bodyAsText().contains("NOT_FOUND"), res.bodyAsText())
    }

    @Test
    fun otherUsersFile404() = testApplication {
        setup()
        val body = "0123456789abcdefghij".encodeToByteArray()
        val fileId = uploadAndGetId(body, "private.txt")
        val otherAuth = setupSecondUser()
        val res = client.get("/api/files/$fileId/content") { header(HttpHeaders.Authorization, otherAuth) }
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertTrue(res.bodyAsText().contains("NOT_FOUND"), res.bodyAsText())
    }

    @Test
    fun rootServesWebIndex() = testApplication {
        setup()
        val root = client.get("/")
        assertEquals(HttpStatusCode.OK, root.status)
        assertTrue(root.bodyAsText().contains("<html", ignoreCase = true), root.bodyAsText())
        // SPA fallback: an unknown client route still serves the index page.
        val spa = client.get("/some/spa/route")
        assertEquals(HttpStatusCode.OK, spa.status)
        assertTrue(spa.bodyAsText().contains("<html", ignoreCase = true), spa.bodyAsText())
        // API and health routes must still win over static hosting.
        val health = client.get("/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertEquals("""{"status":"ok"}""", health.bodyAsText())
    }

    @Test
    fun malformedMimeTypeStillDownloads() = testApplication {
        setup()
        val body = "mime-poison".encodeToByteArray()
        // Upload init stores mimeType verbatim; a malformed value must not turn every
        // download of this file into a 500 - response falls back to octet-stream.
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"bad.bin","size":${body.size},"mimeType":"not a valid mime type"}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val uploadId = json.decodeFromString<UploadInitResponse>(init.bodyAsText()).uploadId
        val put = client.put("/api/uploads/$uploadId/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(body) }
        assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
        val done = client.post("/api/uploads/$uploadId/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        val fileId = json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
        val res = client.get("/api/files/$fileId/content") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals(body.decodeToString(), res.bodyAsText())
    }

    @Test
    fun recentFilesSortedByUpdatedAt() = testApplication {
        setup()
        // Upload three files, then rewrite updated_at so the recency order is
        // newest = old.txt, regardless of upload order.
        for (name in listOf("a.txt", "b.txt", "old.txt")) {
            val res = doUpload("$name-content".encodeToByteArray(), name)
            assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        }
        transaction {
            FilesTable.selectAll().forEach { row ->
                val id = row[FilesTable.id]
                val name = row[FilesTable.name]
                val bumped = when (name) {
                    "old.txt" -> System.currentTimeMillis() + 10_000
                    "a.txt" -> System.currentTimeMillis() + 5_000
                    else -> System.currentTimeMillis()
                }
                FilesTable.update({ FilesTable.id eq id }) { it[updatedAt] = bumped }
            }
        }
        val res = client.get("/api/files/recent") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val recent = json.decodeFromString<RecentFilesResponse>(res.bodyAsText())
        assertEquals(listOf("old.txt", "a.txt", "b.txt"), recent.files.map { it.name })

        // limit parameter: only the single most recent file.
        val limited = client.get("/api/files/recent?limit=1") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, limited.status)
        assertEquals(listOf("old.txt"), json.decodeFromString<RecentFilesResponse>(limited.bodyAsText()).files.map { it.name })

        // Other users must not see these files.
        val otherAuth = setupSecondUser()
        val theirs = client.get("/api/files/recent") { header(HttpHeaders.Authorization, otherAuth) }
        assertEquals(0, json.decodeFromString<RecentFilesResponse>(theirs.bodyAsText()).files.size)
    }
}
