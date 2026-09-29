package com.linan.barezen_drive

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.ContentsResponse
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.files.UploadService
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.StorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * Cover generation after a complete runs in the background, where a failure can
 * never surface to the client - so the only record of it was nothing at all. A
 * file with no cover, a 500 the moment the disk is full and an ffmpeg timeout
 * all looked identical from the outside, and the only way to find out which one
 * it was, was to reproduce it with a debugger attached.
 *
 * These tests pin the log line: which file, and why. They also pin that the
 * upload itself is unaffected - a cover failure is still not an upload failure.
 */
class UploadThumbnailFailureLogTest {
    private val storageDir = Files.createTempDirectory("bz-thumbfail").toString()
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

    /**
     * Fails the storage probe for a cover key, but only from the *second* one on.
     *
     * `complete()` probes the cover itself before it decides to generate one, so
     * the first probe is the request path and the second is the background one -
     * which is the only way to make a background-only failure observable without
     * failing the request that triggers it.
     */
    private class CoverProbeFailureStorage(private val inner: StorageProvider) : StorageProvider {
        private val coverProbes = AtomicInteger()

        override fun blobKey(sha256: String): String = inner.blobKey(sha256)
        override suspend fun put(key: String, channel: ByteReadChannel) = inner.put(key, channel)
        override suspend fun get(key: String): ByteReadChannel = inner.get(key)
        override suspend fun delete(key: String) = inner.delete(key)
        override val tmpDir: Path get() = inner.tmpDir
        override fun resolvePath(key: String): Path? = inner.resolvePath(key)

        override suspend fun exists(key: String): Boolean {
            if (key.startsWith("thumbs/") && coverProbes.incrementAndGet() >= 2) {
                throw IOException("injected cover-probe failure")
            }
            return inner.exists(key)
        }
    }

    private suspend fun ApplicationTestBuilder.setup(storage: StorageProvider) {
        val c = cfg()
        application { module(c, storage) }
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

    /** A real 1x1 JPEG, so generation would succeed if the probe had not thrown. */
    private fun jpeg(): ByteArray {
        val img = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 0xFF0000.toInt())
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private suspend fun captureLog(block: suspend (List<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger(UploadService::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        val previous = logger.level
        logger.level = Level.WARN
        try {
            block(appender.list)
        } finally {
            logger.level = previous
            logger.detachAppender(appender)
        }
    }

    /** Background generation is fire-and-forget: give it a moment to fail. */
    private fun awaitLog(events: List<ILoggingEvent>, needle: String, timeoutMs: Long = 10_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            events.firstOrNull { it.formattedMessage.contains(needle) }?.let { return it.formattedMessage }
            Thread.sleep(25)
        }
        return null
    }

    @Test
    fun aFailedBackgroundCoverIsLoggedWithTheFileIdAndTheCause() = testApplication {
        setup(CoverProbeFailureStorage(LocalStorageProvider(Path.of(storageDir))))
        val body = jpeg()

        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"pic.jpg","size":${body.size},"mimeType":"image/jpeg","sha256":"${sha256hex(body)}"}""")
        }
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        val put = client.put("/api/uploads/${ir.uploadId}/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(body) }
        assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())

        var fileId = ""
        var status: HttpStatusCode = HttpStatusCode.InternalServerError
        captureLog { events ->
            val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
            status = done.status
            fileId = if (done.status == HttpStatusCode.OK) json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id else ""
            val logged = awaitLog(events, "injected cover-probe failure")
            assertNotNull(logged, "a background cover failure must leave a log line, got: ${events.map { it.formattedMessage }}")
            assertTrue(
                logged.contains(fileId),
                "the log must name the file whose cover failed; looked for $fileId in: $logged",
            )
        }
        assertEquals(HttpStatusCode.OK, status, "the upload itself must not fail because the cover did")
        val contents = client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }
        val file = json.decodeFromString<ContentsResponse>(contents.bodyAsText()).files.single()
        assertEquals(false, file.hasThumbnail, "a failed cover must leave the flag down, not claim a cover that is not there")
    }
}
