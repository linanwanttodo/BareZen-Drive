package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.*
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class UploadTest {
    private val storageDir = Files.createTempDirectory("bz-up").toString()
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

    private suspend fun ApplicationTestBuilder.doUpload(body: ByteArray, name: String, sha: String? = null, folderId: String? = null): HttpResponse {
        val initBody = buildString {
            append("""{"name":"$name","size":${body.size}""")
            if (folderId != null) append(""","folderId":"$folderId"""")
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

    // 2.5 MiB deterministic body: with 1 MiB chunkSize this forces 3 chunks (1 MiB, 1 MiB, 0.5 MiB).
    private fun bigBody(): ByteArray = ByteArray(2 * 1024 * 1024 + 512 * 1024) { i -> (i % 251).toByte() }

    @Test
    fun multiChunkUploadListsFileWithSizeAndSha() = testApplication {
        setup()
        val body = bigBody()
        val res = doUpload(body, "notes.bin", sha = sha256hex(body))
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val done = json.decodeFromString<UploadCompleteResponse>(res.bodyAsText())
        assertEquals(body.size.toLong(), done.file.size)
        assertEquals(sha256hex(body), done.file.sha256)
        val list = client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, list.status)
        val contents = json.decodeFromString<ContentsResponse>(list.bodyAsText())
        val listed = contents.files.single { it.name == "notes.bin" }
        assertEquals(body.size.toLong(), listed.size)
        assertEquals(sha256hex(body), listed.sha256)
    }

    @Test
    fun instantUploadBySha() = testApplication {
        setup()
        val body = "same-content-xyz".encodeToByteArray()
        doUpload(body, "a.txt")
        val res = doUpload(body, "b.txt", sha = sha256hex(body))
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(res.bodyAsText())
        assertTrue(ir.instantUpload)
        assertEquals("b.txt", ir.file?.name)
        assertEquals(sha256hex(body), ir.file?.sha256)
    }

    @Test
    fun resumeWithReceivedChunks() = testApplication {
        setup()
        val body = bigBody()
        val initBody = """{"name":"r.bin","size":${body.size},"chunkSize":${1024L * 1024}}"""
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody(initBody) }
        assertEquals(HttpStatusCode.OK, init.status)
        val uploadId = json.decodeFromString<UploadInitResponse>(init.bodyAsText()).uploadId
        client.put("/api/uploads/$uploadId/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(body.copyOfRange(0, 1024 * 1024)) }
        // Simulated reconnect: re-init with the same parameters must return the same
        // session and the already received chunk indexes.
        val re = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody(initBody) }
        assertEquals(HttpStatusCode.OK, re.status, re.bodyAsText())
        val rir = json.decodeFromString<UploadInitResponse>(re.bodyAsText())
        assertEquals(uploadId, rir.uploadId)
        assertEquals(listOf(0), rir.receivedChunks)
        client.put("/api/uploads/$uploadId/chunks/1") { header(HttpHeaders.Authorization, auth); setBody(body.copyOfRange(1024 * 1024, 2 * 1024 * 1024)) }
        client.put("/api/uploads/$uploadId/chunks/2") { header(HttpHeaders.Authorization, auth); setBody(body.copyOfRange(2 * 1024 * 1024, body.size)) }
        val done = client.post("/api/uploads/$uploadId/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
    }

    @Test
    fun completeMissingChunkFails() = testApplication {
        setup()
        val body = bigBody()
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"m.bin","size":${body.size},"chunkSize":${1024L * 1024}}""") }
        val uploadId = json.decodeFromString<UploadInitResponse>(init.bodyAsText()).uploadId
        client.put("/api/uploads/$uploadId/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(body.copyOfRange(0, 1024 * 1024)) }
        val done = client.post("/api/uploads/$uploadId/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.BadRequest, done.status); assertTrue(done.bodyAsText().contains("CHUNK_MISSING"))
    }

    @Test
    fun wrongChunkShaRejected() = testApplication {
        setup()
        val chunk = ByteArray(1024 * 1024)
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"s.bin","size":${chunk.size},"chunkSize":${chunk.size}}""") }
        val uploadId = json.decodeFromString<UploadInitResponse>(init.bodyAsText()).uploadId
        val bad = client.put("/api/uploads/$uploadId/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(chunk); header("X-Chunk-Sha256", "deadbeef" + "0".repeat(56)) }
        assertEquals(HttpStatusCode.BadRequest, bad.status); assertTrue(bad.bodyAsText().contains("CHUNK_INVALID"))
        // The partial part file must not survive a rejected chunk.
        val sessionDir = java.nio.file.Path.of(storageDir, "tmp", uploadId).toFile()
        assertFalse(sessionDir.exists() && sessionDir.listFiles()?.isNotEmpty() == true, "rejected chunk must be cleaned up")
    }

    @Test
    fun oversizedChunkRejected() = testApplication {
        setup()
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"big.bin","size":16,"chunkSize":${1024L * 1024}}""") }
        val uploadId = json.decodeFromString<UploadInitResponse>(init.bodyAsText()).uploadId
        // 1 MiB + 1 byte against a 1 MiB expectation: the stream guard must trip.
        val oversize = ByteArray(1024 * 1024 + 1)
        val res = client.put("/api/uploads/$uploadId/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(oversize) }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("CHUNK_INVALID"))
    }

    @Test
    fun abortCleansSession() = testApplication {
        setup()
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"x.bin","size":16,"chunkSize":${1024L * 1024}}""") }
        val uploadId = json.decodeFromString<UploadInitResponse>(init.bodyAsText()).uploadId
        val del = client.delete("/api/uploads/$uploadId") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NoContent, del.status)
        val done = client.post("/api/uploads/$uploadId/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NotFound, done.status)
    }

    @Test
    fun deleteFolderAbortsOpenSessions() = testApplication {
        setup()
        val mk = client.post("/api/folders") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"F"}""") }
        assertEquals(HttpStatusCode.Created, mk.status)
        val folderId = Regex(""""id":"([^"]+)"""").find(mk.bodyAsText())!!.groupValues[1]
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"pending.bin","size":16,"chunkSize":${1024L * 1024},"folderId":"$folderId"}""") }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val uploadId = json.decodeFromString<UploadInitResponse>(init.bodyAsText()).uploadId
        val del = client.delete("/api/folders/$folderId") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NoContent, del.status)
        val done = client.post("/api/uploads/$uploadId/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NotFound, done.status)
    }

    @Test
    fun deleteFolderRemovesTerminalSessions() = testApplication {
        setup()
        // A COMPLETED session targeting the folder also holds the folder_id FK reference;
        // deleting the folder must succeed (previously 500).
        val mk = client.post("/api/folders") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"G"}""") }
        assertEquals(HttpStatusCode.Created, mk.status)
        val folderId = Regex(""""id":"([^"]+)"""").find(mk.bodyAsText())!!.groupValues[1]
        val res = doUpload("done-content-1234".encodeToByteArray(), "f.bin", folderId = folderId)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val del = client.delete("/api/folders/$folderId") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NoContent, del.status)
        val list = json.decodeFromString<ContentsResponse>(client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }.bodyAsText())
        assertFalse(list.folders.any { it.name == "G" })
    }

    @Test
    fun renameMoveDeletePositive() = testApplication {
        setup()
        val body = "0123456789abcdefghij".encodeToByteArray()
        val sha = sha256hex(body)
        val ra = doUpload(body, "fileA.txt")
        assertEquals(HttpStatusCode.OK, ra.status, ra.bodyAsText())
        val aId = json.decodeFromString<UploadCompleteResponse>(ra.bodyAsText()).file.id
        val rb = doUpload(body, "fileB.txt", sha = sha)
        assertEquals(HttpStatusCode.OK, rb.status, rb.bodyAsText())
        assertTrue(json.decodeFromString<UploadInitResponse>(rb.bodyAsText()).instantUpload,
            "second upload of identical content must be an instant upload")
        // Rename A
        val ren = client.patch("/api/files/$aId") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"renamedA.txt"}""") }
        assertEquals(HttpStatusCode.OK, ren.status, ren.bodyAsText())
        var list = json.decodeFromString<ContentsResponse>(client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }.bodyAsText())
        assertTrue(list.files.any { it.name == "renamedA.txt" })
        assertFalse(list.files.any { it.name == "fileA.txt" })
        // Move A into a new folder
        val mk = client.post("/api/folders") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"Dest"}""") }
        assertEquals(HttpStatusCode.Created, mk.status)
        val destId = Regex(""""id":"([^"]+)"""").find(mk.bodyAsText())!!.groupValues[1]
        val mv = client.patch("/api/files/$aId") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"folderId":"$destId"}""") }
        assertEquals(HttpStatusCode.OK, mv.status, mv.bodyAsText())
        list = json.decodeFromString<ContentsResponse>(client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }.bodyAsText())
        assertFalse(list.files.any { it.name == "renamedA.txt" })
        val destList = json.decodeFromString<ContentsResponse>(client.get("/api/folders/$destId/contents") { header(HttpHeaders.Authorization, auth) }.bodyAsText())
        assertTrue(destList.files.any { it.name == "renamedA.txt" })
        // Delete A (blob refcount 2 -> 1, blob kept), then B (refcount 0, blob deleted)
        val delA = client.delete("/api/files/$aId") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NoContent, delA.status)
        val bId = json.decodeFromString<UploadCompleteResponse>(rb.bodyAsText()).file.id
        val delB = client.delete("/api/files/$bId") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NoContent, delB.status)
        // After both deletions the blob must be gone, so init with the same sha is NOT instant.
        val reInit = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"fresh.txt","size":${body.size},"sha256":"$sha"}""") }
        assertEquals(HttpStatusCode.OK, reInit.status, reInit.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(reInit.bodyAsText())
        assertFalse(ir.instantUpload)
    }

    @Test
    fun sessionExpiryRejected() = testApplication {
        setup()
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"old.bin","size":16,"chunkSize":${1024L * 1024}}""") }
        val uploadId = json.decodeFromString<UploadInitResponse>(init.bodyAsText()).uploadId
        // Force the session to be expired by rewriting expires_at into the past.
        transaction { UploadSessionsTable.update({ UploadSessionsTable.id eq UUID.fromString(uploadId) }) { it[expiresAt] = System.currentTimeMillis() - 10_000 } }
        val put = client.put("/api/uploads/$uploadId/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(ByteArray(16)) }
        assertEquals(HttpStatusCode.Conflict, put.status); assertTrue(put.bodyAsText().contains("SESSION_EXPIRED"))
    }

    @Test
    fun expiredSessionIsNotResumed() = testApplication {
        setup()
        val initBody = """{"name":"stale.bin","size":16,"chunkSize":${1024L * 1024}}"""
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody(initBody) }
        val oldId = json.decodeFromString<UploadInitResponse>(init.bodyAsText()).uploadId
        transaction { UploadSessionsTable.update({ UploadSessionsTable.id eq UUID.fromString(oldId) }) { it[expiresAt] = System.currentTimeMillis() - 10_000 } }
        // Re-init with the same name/size must NOT hand back the dead session...
        val re = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody(initBody) }
        val newId = json.decodeFromString<UploadInitResponse>(re.bodyAsText()).uploadId
        assertNotEquals(oldId, newId)
        // ...and the fresh session accepts chunks.
        val put = client.put("/api/uploads/$newId/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(ByteArray(16)) }
        assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
    }

    @Test
    fun initRejectsFilesOverConfiguredCap() = testApplication {
        val tiny = AppConfig(0, "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1024L)
        val storage = LocalStorageProvider(java.nio.file.Path.of(tiny.storageDir))
        application { module(tiny, storage) }
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        val login = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
        val init = client.post("/api/uploads/init") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"huge.bin","size":4096}""") }
        assertEquals(HttpStatusCode.PayloadTooLarge, init.status)
        assertTrue(init.bodyAsText().contains("FILE_TOO_LARGE"))
    }

    @Test
    fun instantUploadIgnoresLyingSize() = testApplication {
        setup()
        val body = "truth-size".encodeToByteArray()
        doUpload(body, "real.txt")
        // Same content, but the client claims a bogus size on the instant path.
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json)
            setBody("""{"name":"clone.txt","size":999999,"sha256":"${sha256hex(body)}"}""")
        }
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        assertTrue(ir.instantUpload)
        assertEquals(body.size.toLong(), ir.file?.size)
    }

    @Test
    fun searchTreatsPercentLiterally() = testApplication {
        setup()
        doUpload("x".encodeToByteArray(), "50%off.txt")
        doUpload("y".encodeToByteArray(), "plain.txt")
        // A bare "%" must not act as a match-everything wildcard: it matches only
        // the file whose name literally contains a percent sign.
        val wildcard = client.get("/api/search?q=%25") { header(HttpHeaders.Authorization, auth) }
        val w = json.decodeFromString<RecentFilesResponse>(wildcard.bodyAsText())
        assertEquals(listOf("50%off.txt"), w.files.map { it.name })
        // But a literal percent in the query still matches names containing it.
        val hit = client.get("/api/search?q=50%25") { header(HttpHeaders.Authorization, auth) }
        val h = json.decodeFromString<RecentFilesResponse>(hit.bodyAsText())
        assertEquals(listOf("50%off.txt"), h.files.map { it.name })
    }
}
