package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.FileVersionDto
import com.linan.barezen_drive.core.dto.FileVersionsResponse
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.files.VersionService
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

/**
 * File versions: an overwrite upload snapshots the previous content, the file
 * row keeps its identity (id, shares, flags), and the version endpoints can
 * list / restore / drop revisions. Reference counting now spans both tables.
 */
class FileVersionTest {
    private val storageDir = Files.createTempDirectory("bz-versions").toString()
    private fun cfg() = AppConfig(0, "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30)
    private var auth = ""
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(Path.of(c.storageDir))) }
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        val login = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    private fun sha256hex(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private suspend fun ApplicationTestBuilder.initUpload(name: String, content: ByteArray, overwrite: Boolean = false): UploadInitResponse {
        val initBody = buildString {
            append("""{"name":"$name","size":${content.size},"mimeType":"text/plain","sha256":"${sha256hex(content)}"""")
            if (overwrite) append(""","overwrite":true""")
            append("}")
        }
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody(initBody)
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        return json.decodeFromString(init.bodyAsText())
    }

    /** Full upload through chunks so the sha is fresh (no instant hit). */
    private suspend fun ApplicationTestBuilder.upload(name: String, content: ByteArray, overwrite: Boolean = false): UploadCompleteResponse {
        val ir = initUpload(name, content, overwrite)
        if (ir.instantUpload) return UploadCompleteResponse(ir.file!!)
        val put = client.put("/api/uploads/${ir.uploadId}/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(content) }
        assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
        val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return json.decodeFromString(done.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.content(id: String): String {
        val res = client.get("/api/files/$id/content") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return res.bodyAsText()
    }

    private suspend fun ApplicationTestBuilder.versions(id: String): List<FileVersionDto> {
        val res = client.get("/api/files/$id/versions") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return json.decodeFromString<FileVersionsResponse>(res.bodyAsText()).versions
    }

    @Test
    fun overwriteSnapshotsAndRestoreSwaps() = testApplication {
        setup()
        val v1 = upload("doc.txt", "one".encodeToByteArray()).file
        val over = upload("doc.txt", "two-two".encodeToByteArray(), overwrite = true)
        assertEquals(v1.id, over.file.id, "overwrite keeps the file identity")
        assertNotNull(over.replacedVersion, "previous content must be snapshotted")
        assertEquals(1L, over.replacedVersion!!.revision)
        assertEquals(sha256hex("one".encodeToByteArray()), over.replacedVersion!!.sha256)
        assertEquals("two-two", content(v1.id))

        var vs = versions(v1.id)
        assertEquals(1, vs.size)
        val restore = client.post("/api/files/${v1.id}/versions/${vs.first().id}/restore") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, restore.status, restore.bodyAsText())
        assertEquals("one", content(v1.id))
        // The restore response is the refreshed live row.
        assertEquals(sha256hex("one".encodeToByteArray()), json.decodeFromString<FileDto>(restore.bodyAsText()).sha256)
        // The displaced head is now version 2; the adopted revision left the list.
        vs = versions(v1.id)
        assertEquals(listOf(2L), vs.map { it.revision })
        assertEquals(sha256hex("two-two".encodeToByteArray()), vs.first().sha256)
    }

    @Test
    fun conflictWithoutOverwriteStillRejected() = testApplication {
        setup()
        val a = upload("dup.txt", "a".encodeToByteArray()).file
        val ir = initUpload("dup.txt", "b".encodeToByteArray(), overwrite = false)
        val put = client.put("/api/uploads/${ir.uploadId}/chunks/0") { header(HttpHeaders.Authorization, auth); setBody("b".encodeToByteArray()) }
        assertEquals(HttpStatusCode.NoContent, put.status)
        val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.Conflict, done.status)
        assertTrue(done.bodyAsText().contains("NAME_CONFLICT"))
        // The original row survived the rejected attempt untouched.
        assertEquals("a", content(a.id))
        assertEquals(emptyList<FileVersionDto>(), versions(a.id))
    }

    @Test
    fun instantOverwriteSnapshotsExistingBlob() = testApplication {
        setup()
        val a = upload("a.txt", "AAAA".encodeToByteArray()).file
        upload("b.txt", "BBBB".encodeToByteArray())
        // Re-upload B's bytes onto A's name with overwrite: instant (blob known),
        // and A's old content lands in history.
        val ir = initUpload("a.txt", "BBBB".encodeToByteArray(), overwrite = true)
        assertTrue(ir.instantUpload, "content-addressed blob must short-circuit the transfer")
        assertEquals(a.id, ir.file!!.id, "same id after an instant overwrite")
        assertEquals("BBBB", content(a.id))
        val vs = versions(a.id)
        assertEquals(1, vs.size)
        assertEquals(sha256hex("AAAA".encodeToByteArray()), vs.first().sha256)
        // A restored revision and its blob both stay reachable.
        val restore = client.post("/api/files/${a.id}/versions/${vs.first().id}/restore") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, restore.status, restore.bodyAsText())
        assertEquals("AAAA", content(a.id))
    }

    @Test
    fun identicalOverwriteDoesNotSnapshot() = testApplication {
        setup()
        val a = upload("same.txt", "xyz".encodeToByteArray()).file
        val ir = initUpload("same.txt", "xyz".encodeToByteArray(), overwrite = true)
        assertTrue(ir.instantUpload)
        assertEquals(a.id, ir.file!!.id)
        assertEquals(sha256hex("xyz".encodeToByteArray()), ir.file!!.sha256)
        assertEquals(emptyList<FileVersionDto>(), versions(a.id))
    }

    @Test
    fun trimDropsOldestBeyondCap() = testApplication {
        setup()
        val first = upload("cap.txt", "c0".encodeToByteArray()).file
        repeat(VersionService.MAX_VERSIONS + 3) { i ->
            upload("cap.txt", "content-$i".encodeToByteArray(), overwrite = true)
        }
        val vs = versions(first.id)
        assertEquals(VersionService.MAX_VERSIONS, vs.size, "history is capped, oldest revisions go first")
        assertEquals((4L..(VersionService.MAX_VERSIONS + 3).toLong()).reversed().toList(), vs.map { it.revision })
    }

    @Test
    fun versionDeleteRemovesRowAndPurgesExclusiveBlob() = testApplication {
        setup()
        val a = upload("del.txt", "v1".encodeToByteArray()).file
        upload("del.txt", "v2".encodeToByteArray(), overwrite = true)
        val vs = versions(a.id)
        val vSha = vs.first().sha256
        val blob = Path.of(storageDir, "blobs", vSha.substring(0, 2), vSha.substring(2, 4), vSha)
        assertTrue(Files.exists(blob), "version blob exists while referenced")
        val del = client.delete("/api/files/${a.id}/versions/${vs.first().id}") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NoContent, del.status, del.bodyAsText())
        assertEquals(emptyList<FileVersionDto>(), versions(a.id))
        assertFalse(Files.exists(blob), "dropping the last reference must free the blob")
        // The live content is untouched.
        assertEquals("v2", content(a.id))
    }

    @Test
    fun deleteForeverAlsoPurgesVersionBlobs() = testApplication {
        setup()
        val a = upload("gone.txt", "g1".encodeToByteArray()).file
        upload("gone.txt", "g2".encodeToByteArray(), overwrite = true)
        val vs = versions(a.id)
        val vSha = vs.first().sha256
        val blob = Path.of(storageDir, "blobs", vSha.substring(0, 2), vSha.substring(2, 4), vSha)
        assertTrue(Files.exists(blob))
        client.delete("/api/files/${a.id}") { header(HttpHeaders.Authorization, auth) }
        client.delete("/api/trash/${a.id}") { header(HttpHeaders.Authorization, auth) }
        assertFalse(Files.exists(blob), "hard-deleting the file must cascade its versions")
    }

    @Test
    fun folderConflictCannotBeOverwritten() = testApplication {
        setup()
        val mk = client.post("/api/folders") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"fold"}""")
        }
        assertEquals(HttpStatusCode.Created, mk.status)
        // Even with overwrite a folder of the same name is not a replace target.
        val ir = initUpload("fold", "x".encodeToByteArray(), overwrite = true)
        assertFalse(ir.instantUpload)
        val put = client.put("/api/uploads/${ir.uploadId}/chunks/0") { header(HttpHeaders.Authorization, auth); setBody("x".encodeToByteArray()) }
        assertEquals(HttpStatusCode.NoContent, put.status)
        val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.Conflict, done.status)
    }

    @Test
    fun versionsRequireOwnershipAndValidIds() = testApplication {
        setup()
        val a = upload("own.txt", "1".encodeToByteArray()).file
        upload("own.txt", "2".encodeToByteArray(), overwrite = true)
        val bad = client.get("/api/files/not-a-uuid/versions") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val missing = client.get("/api/files/${UUID.randomUUID()}/versions") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NotFound, missing.status)
        val vs = versions(a.id)
        // Restoring a version id that belongs to no such file is a 404.
        val rogue = client.post("/api/files/${a.id}/versions/${UUID.randomUUID()}/restore") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NotFound, rogue.status)
        assertEquals(1, vs.size)
    }
}
