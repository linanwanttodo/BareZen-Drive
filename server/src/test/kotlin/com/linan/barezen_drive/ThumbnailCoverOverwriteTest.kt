package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.ContentsResponse
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.files.ThumbnailService
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

/**
 * A cover is stored under a key derived from the *source* content hash, so every
 * copy of a file shares one cover - which made the key behave like an immutable
 * object: the storage layer skipped the write whenever the key already existed.
 * The consequences were that a cover the client had to redo (a bad frame, a
 * failed transfer, a "regenerate" tap) could never land, and that a cover the
 * server had generated could never be improved upon.
 *
 * The fix is to let cover writes replace the stored bytes. What has to survive
 * that is the idempotence that actually matters: repeating the same PUT must
 * still converge on the same cover, and the flag write-back still covers every
 * row of the same content.
 */
class ThumbnailCoverOverwriteTest {
    private val storageDir = Files.createTempDirectory("bz-cover").toString()
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
        application { module(c, LocalStorageProvider(Path.of(c.storageDir))) }
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

    private suspend fun ApplicationTestBuilder.upload(body: ByteArray, name: String, mime: String): String {
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"mimeType":"$mime","sha256":"${sha256hex(body)}"}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        if (ir.instantUpload) return ir.file!!.id
        val put = client.put("/api/uploads/${ir.uploadId}/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(body) }
        assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
        val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
    }

    private suspend fun ApplicationTestBuilder.putCover(fileId: String, cover: ByteArray): HttpResponse =
        client.put("/api/files/$fileId/thumbnail") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Image.JPEG); setBody(cover)
        }

    private suspend fun ApplicationTestBuilder.cover(fileId: String): ByteArray {
        val get = client.get("/api/files/$fileId/thumbnail") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, get.status, get.bodyAsText())
        return get.bodyAsBytes()
    }

    private fun fakeJpeg(marker: Int) =
        ByteArray(512) { (it + marker).toByte() } + byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun aSecondPutReplacesTheStoredCover() = testApplication {
        setup()
        val fileId = upload("source-bytes".encodeToByteArray(), "pic.jpg", "image/jpeg")
        val bad = fakeJpeg(1)
        val better = fakeJpeg(200)
        assertEquals(HttpStatusCode.OK, putCover(fileId, bad).status)

        val put = putCover(fileId, better)

        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        assertTrue(
            better.contentEquals(cover(fileId)),
            "the newer cover must be the one that is served; a cover slot that cannot " +
                "be written twice can never be fixed",
        )
    }

    @Test
    fun aRepeatedIdenticalPutConvergesOnTheSameCover() = testApplication {
        setup()
        val fileId = upload("same-content".encodeToByteArray(), "again.jpg", "image/jpeg")
        val cover = fakeJpeg(3)
        assertEquals(HttpStatusCode.OK, putCover(fileId, cover).status)
        // A client that never saw the first response retries the exact same PUT.
        val retry = putCover(fileId, cover)

        assertEquals(HttpStatusCode.OK, retry.status, retry.bodyAsText())
        assertTrue(cover.contentEquals(cover(fileId)), "re-sending the same cover must not change what is served")
        val files = json.decodeFromString<ContentsResponse>(
            client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }.bodyAsText(),
        ).files
        assertEquals(listOf(true), files.map { it.hasThumbnail })
    }

    @Test
    fun aCoverOfExactlyTheCapIsAccepted() = testApplication {
        setup()
        val fileId = upload("cap-edge".encodeToByteArray(), "cap.jpg", "image/jpeg")
        val cap = 512 * 1024
        val atCap = ByteArray(cap) { 3 }

        val put = putCover(fileId, atCap)

        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        val overCap = client.put("/api/files/$fileId/thumbnail") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Image.JPEG)
            setBody(ByteArray(cap + 1) { 3 })
        }
        assertEquals(HttpStatusCode.BadRequest, overCap.status, overCap.bodyAsText())
    }

    /**
     * Which keys a write may replace is decided by the key itself, not by a
     * flag a caller can get wrong: a cover (or avatar) slot always takes the new
     * bytes, a content-addressed blob never does.
     */
    @Test
    fun aCoverSlotIsReplaceableWhileABlobKeyIsNot() = runTest {
        val storage = LocalStorageProvider(Files.createTempDirectory("bz-coverstore"))
        val coverKey = "thumbs/" + "c".repeat(64) + ".jpg"
        storage.put(coverKey, ByteReadChannel("bad-cover".encodeToByteArray()))
        storage.put(coverKey, ByteReadChannel("better-cover".encodeToByteArray()))
        assertEquals(
            "better-cover", storage.get(coverKey).toByteArray().decodeToString(),
            "a cover slot must take the newer cover, or a bad one can never be fixed",
        )
        // Content-addressed keys keep the skip: every row referencing this key
        // serves it, so replacing the bytes would change what all of them mean.
        val blob = storage.blobKey("d".repeat(64))
        storage.put(blob, ByteReadChannel("blob-v1".encodeToByteArray()))
        storage.put(blob, ByteReadChannel("blob-v2".encodeToByteArray()))
        assertEquals("blob-v1", storage.get(blob).toByteArray().decodeToString())
    }

    /** An avatar is the same shape as a cover: one per owner, replaced on change. */
    @Test
    fun anAvatarSlotIsReplaceableToo() = runTest {
        val storage = LocalStorageProvider(Files.createTempDirectory("bz-avatar"))
        val avatar = "avatars/11111111-1111-1111-1111-111111111111.jpg"
        storage.put(avatar, ByteReadChannel("face-v1".encodeToByteArray()))
        storage.put(avatar, ByteReadChannel("face-v2".encodeToByteArray()))
        assertEquals(
            "face-v2", storage.get(avatar).toByteArray().decodeToString(),
            "changing an avatar has to replace it; skipping left the first one forever",
        )
    }

    @Test
    fun theCapIsOneConstant() {
        assertEquals(
            512 * 1024, ThumbnailService.MAX_THUMB_BYTES,
            "the generator and the PUT route must share one cover size cap",
        )
    }
}
