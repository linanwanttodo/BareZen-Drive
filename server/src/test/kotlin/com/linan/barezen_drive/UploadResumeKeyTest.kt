package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
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
 * Resume matching used to key on (user, folder, name, size, still open). Two
 * uploads of the same name and size therefore landed in ONE session, and both
 * wrote the same `$index.part` slot - the second writer silently replaced the
 * first, so the merge could end up a mixture of two files.
 *
 * The content hash belongs in the match key: same bytes means the same upload
 * and may resume, different bytes may not. What this must not break is the other
 * direction - a client that reconnects mid-upload re-inits with the same
 * parameters and has to get its session (and its already received chunks) back.
 */
class UploadResumeKeyTest {
    private val storageDir = Files.createTempDirectory("bz-resume").toString()
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

    private suspend fun ApplicationTestBuilder.init(name: String, size: Long, sha: String?): UploadInitResponse {
        val body = buildString {
            append("""{"name":"$name","size":$size,"chunkSize":${1024 * 1024}""")
            if (sha != null) append(""","sha256":"$sha"""")
            append("}")
        }
        val res = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody(body)
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return json.decodeFromString(res.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.putChunk(uploadId: String, index: Int, body: ByteArray) {
        val put = client.put("/api/uploads/$uploadId/chunks/$index") {
            header(HttpHeaders.Authorization, auth); setBody(body)
        }
        assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.complete(uploadId: String) {
        val done = client.post("/api/uploads/$uploadId/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
    }

    /** 2.5 MiB so the whole flow needs three chunks. */
    private fun payload(seed: Int): ByteArray = ByteArray(2 * 1024 * 1024 + 512 * 1024) { (it + seed % 251).toByte() }

    @Test
    fun reconnectingWithTheSameContentResumesTheSameSession() = testApplication {
        setup()
        val body = payload(1)
        val sha = sha256hex(body)
        val first = init("report.bin", body.size.toLong(), sha)
        putChunk(first.uploadId, 0, body.copyOfRange(0, 1024 * 1024))

        // The client dropped its connection and re-inits with identical parameters.
        val again = init("report.bin", body.size.toLong(), sha)

        assertEquals(first.uploadId, again.uploadId, "a genuine resume must get its own session back")
        assertEquals(listOf(0), again.receivedChunks, "the already received chunk must be reported again")
        putChunk(again.uploadId, 1, body.copyOfRange(1024 * 1024, 2 * 1024 * 1024))
        putChunk(again.uploadId, 2, body.copyOfRange(2 * 1024 * 1024, body.size))
        complete(again.uploadId)
    }

    @Test
    fun aClientThatNeverSentAHashStillResumes() = testApplication {
        setup()
        val body = payload(2)
        val first = init("legacy.bin", body.size.toLong(), null)
        putChunk(first.uploadId, 0, body.copyOfRange(0, 1024 * 1024))

        val again = init("legacy.bin", body.size.toLong(), null)

        assertEquals(first.uploadId, again.uploadId, "an unchanged client must keep resuming its session")
        assertEquals(listOf(0), again.receivedChunks)
    }

    @Test
    fun twoParallelUploadsOfTheSameNameAndSizeDoNotShareOneSession() = testApplication {
        setup()
        val a = payload(3)
        val b = payload(4) // same length, different content
        assertEquals(a.size, b.size, "the collision needs an identical name and size")
        val first = init("holiday.mp4", a.size.toLong(), sha256hex(a))
        putChunk(first.uploadId, 0, a.copyOfRange(0, 1024 * 1024))

        val second = init("holiday.mp4", b.size.toLong(), sha256hex(b))

        assertNotEquals(
            first.uploadId, second.uploadId,
            "different content under the same name must not be folded into the running session",
        )
        assertEquals(
            emptyList(), second.receivedChunks,
            "the new session must not inherit the other upload's chunk slots",
        )
        // Each session keeps its own bytes: completing the second one stores b.
        putChunk(second.uploadId, 0, b.copyOfRange(0, 1024 * 1024))
        putChunk(second.uploadId, 1, b.copyOfRange(1024 * 1024, 2 * 1024 * 1024))
        putChunk(second.uploadId, 2, b.copyOfRange(2 * 1024 * 1024, b.size))
        complete(second.uploadId)
    }

    @Test
    fun aHashedUploadDoesNotResumeAnUnhashedSession() = testApplication {
        setup()
        val a = payload(5)
        val b = payload(6)
        val unhashed = init("mixed.bin", a.size.toLong(), null)
        putChunk(unhashed.uploadId, 0, a.copyOfRange(0, 1024 * 1024))

        val hashed = init("mixed.bin", b.size.toLong(), sha256hex(b))

        assertNotEquals(
            unhashed.uploadId, hashed.uploadId,
            "a request that declares content can only resume a session of that same content",
        )
        assertEquals(emptyList(), hashed.receivedChunks)
    }
}
