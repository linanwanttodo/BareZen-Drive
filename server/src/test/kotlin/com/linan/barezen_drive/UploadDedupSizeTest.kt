package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.ContentsResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

/**
 * A dedup (instant) upload has to record the size of the content it is
 * claiming, not the size the request declared - the declared size is only
 * checked against the server cap and never against the blob. The two places that
 * resolve it (overwriting a live name, claiming a free one) used to spell the
 * rule out separately, and these tests pin that they still agree, including the
 * case where the only source left is the blob's own length.
 */
class UploadDedupSizeTest {
    private val storageDir = Files.createTempDirectory("bz-dedupsize").toString()
    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
        // Stated explicitly: registration is closed by default, and the
        // bootstrap slot only opens for a loopback peer, so a test that
        // registers must say it wants the door open.
        registrationOpen = true,
    )
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    private fun sha256hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private suspend fun ApplicationTestBuilder.upload(body: ByteArray, name: String) {
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"sha256":"${sha256hex(body)}"}""")
        }
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        if (ir.instantUpload) return
        val put = client.put("/api/uploads/${ir.uploadId}/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(body) }
        assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
        val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.initSize(body: ByteArray, name: String, declaredSize: Long, overwrite: Boolean): Long {
        val res = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":$declaredSize,"sha256":"${sha256hex(body)}","overwrite":$overwrite}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(res.bodyAsText())
        assertTrue(ir.instantUpload, "the blob is stored, so this must be a dedup upload: ${res.bodyAsText()}")
        return ir.file!!.size
    }

    @Test
    fun claimingAFreeNameRecordsTheStoredSizeNotTheDeclaredOne() = testApplication {
        setup()
        val body = ByteArray(4096) { (it % 97).toByte() }
        upload(body, "first.bin")

        val size = initSize(body, "second.bin", declaredSize = 999_999, overwrite = false)

        assertEquals(body.size.toLong(), size, "a dedup upload must record the size of the content it claimed")
    }

    @Test
    fun overwritingALiveNameRecordsTheStoredSizeNotTheDeclaredOne() = testApplication {
        setup()
        val original = ByteArray(1024) { 1 }
        val replacement = ByteArray(3072) { 2 }
        upload(original, "doc.bin")
        // The replacement content has to be in the store too: this is the
        // dedup path (the blob already exists) that then overwrites the name.
        upload(replacement, "source-of-replacement.bin")

        val size = initSize(replacement, "doc.bin", declaredSize = 999_999, overwrite = true)

        assertEquals(replacement.size.toLong(), size, "the overwritten row must carry the new content's size")
        val contents = json.decodeFromString<ContentsResponse>(
            client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }.bodyAsText(),
        )
        assertEquals(replacement.size.toLong(), contents.files.single { it.name == "doc.bin" }.size)
    }

    @Test
    fun withNoRowLeftTheBlobItselfDecidesTheSize() = testApplication {
        setup()
        val body = ByteArray(2048) { 3 }
        upload(body, "gone.bin")
        val id = json.decodeFromString<ContentsResponse>(
            client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }.bodyAsText(),
        ).files.single().id
        // Trash it and empty the trash: the row is gone, but the bytes are only
        // queued for deletion (2h grace), so the blob is still on disk.
        client.delete("/api/files/$id") { header(HttpHeaders.Authorization, auth) }
        val emptied = client.delete("/api/trash") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NoContent, emptied.status, emptied.bodyAsText())
        val sha = sha256hex(body)
        val blob = java.nio.file.Path.of(storageDir, "blobs", sha.substring(0, 2), sha.substring(2, 4), sha)
        assertTrue(Files.exists(blob), "the blob must still be on disk: only queued, not unlinked")

        val size = initSize(body, "back-again.bin", declaredSize = 999_999, overwrite = false)

        assertEquals(
            body.size.toLong(), size,
            "with no row referencing the blob, its own length is the only trustworthy size",
        )
    }
}
