package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.files.FileMeta
import com.linan.barezen_drive.files.ThumbnailService
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

/**
 * The ImageIO fallback used to open a stream on the source, bail out on "no
 * reader for this format" *before* the close, and then open the same file a
 * second time to decode it. Every request for a file whose format ImageIO does
 * not know (the WEBP/HEIC/AVIF uploads a phone produces) therefore leaked a file
 * descriptor and read the file twice.
 *
 * The bound is measured, not asserted by inspection: on Linux the number of open
 * descriptors is countable, and a leak shows up as a per-call increment. Garbage
 * bytes are the input that takes the "no reader" exit, which is the exit the
 * first implementation got wrong.
 */
class ThumbnailDecodeHandleTest {
    private val storageDir = Files.createTempDirectory("bz-fd").toString()
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

    /** Open descriptors of this JVM, or -1 where /proc is not available. */
    private fun openDescriptors(): Int {
        val fdDir = Path.of("/proc/self/fd")
        if (!Files.isDirectory(fdDir)) return -1
        Files.list(fdDir).use { s -> return s.count().toInt() }
    }

    @Test
    fun undecodableSourcesDoNotLeakAFileDescriptorPerAttempt() = testApplication {
        setup()
        val attempts = 40
        val sources = (0 until attempts).map { i ->
            // A header ImageIO does not recognise, so the decoder exits on the
            // "no reader" path rather than after a successful read.
            "not-an-image-$i".encodeToByteArray() + ByteArray(4096) { (it + i).toByte() }
        }
        val fileIds = sources.mapIndexed { i, body ->
            val init = client.post("/api/uploads/init") {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"clip-$i.webp","size":${body.size},"mimeType":"image/webp","sha256":"${sha256hex(body)}"}""")
            }
            assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
            val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
            val put = client.put("/api/uploads/${ir.uploadId}/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(body) }
            assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
            val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
            assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
            json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
        }

        val storage = LocalStorageProvider(Path.of(storageDir))
        val metas = fileIds.mapIndexed { i, id ->
            val body = sources[i]
            FileMeta(
                id = UUID.fromString(id), userId = UUID.randomUUID(), folderId = null,
                name = "clip-$i.webp", size = body.size.toLong(), mimeType = "image/webp",
                sha256 = sha256hex(body), storageKey = storage.blobKey(sha256hex(body)), hasThumbnail = false,
            )
        }
        // Warm-up: the first decode initialises the ImageIO plugin registry, which
        // opens files of its own exactly once.
        runBlocking { assertFalse(ThumbnailService.ensureThumbnail(storage, metas.first())) }

        val before = openDescriptors()
        if (before < 0) return@testApplication // not Linux: nothing to count
        runBlocking { metas.forEach { ThumbnailService.ensureThumbnail(storage, it) } }
        val after = openDescriptors()

        assertTrue(
            after <= before + 2,
            "$attempts undecodable sources leaked ${after - before} file descriptors " +
                "(before=$before after=$after)",
        )
    }
}
