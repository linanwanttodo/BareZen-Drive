package com.linan.barezen_drive

import com.linan.barezen_drive.storage.S3StorageProvider
import com.linan.barezen_drive.storage.SigV4
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.runBlocking
import kotlinx.io.readString
import kotlinx.io.readByteArray
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

/**
 * S3StorageProvider against an in-process fake object store (JDK http server,
 * zero new dependencies). The fake re-computes the SigV4 signature from the
 * headers it actually received - so the test also pins down what the JDK
 * client really puts on the wire (Host format included) and rejects any blob
 * whose x-amz-content-sha256 header disagrees with the body.
 */
class S3StorageProviderTest {
    private val bucket = "bz-test"
    private val accessKey = "AKIATESTTESTTEST"
    private val secretKey = "test-secret-test-secret-12345678"
    private val region = "us-east-1"
    private val objects = HashMap<String, ByteArray>()
    private var putCount = 0
    private var lastRange: String? = null
    private lateinit var server: HttpServer
    private lateinit var provider: S3StorageProvider

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @BeforeTest
    fun start() {
        objects.clear(); putCount = 0; lastRange = null
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
        val endpoint = java.net.URI("http://127.0.0.1:${server.address.port}")
        provider = S3StorageProvider(
            client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build(),
            endpoint = endpoint,
            region = region,
            bucket = bucket,
            accessKey = accessKey,
            secretKey = secretKey,
            pathStyle = true,
            tmpDir = Files.createTempDirectory("bz-s3-tmp"),
        )
    }

    @AfterTest
    fun stop() {
        runCatching { server.stop(0) }
    }

    private fun handle(ex: HttpExchange) {
        try {
            val key = ex.requestURI.path.removePrefix("/$bucket/").removePrefix("/$bucket")
            val body = ex.requestBody.readAllBytes()
            val expectedHash = sha(body)
            val auth = verifyAuth(ex, expectedHash)
            if (!auth) {
                ex.sendResponseHeaders(403, -1); return
            }
            when (ex.requestMethod) {
                "PUT" -> { putCount++; objects[key] = body; ex.sendResponseHeaders(200, -1) }
                "GET" -> {
                    val data = objects[key] ?: run { ex.sendResponseHeaders(404, -1); return }
                    val range = ex.requestHeaders.getFirst("range")
                    lastRange = range
                    val slice = if (range == null) data else run {
                        val spec = range.removePrefix("bytes=")
                        val first = spec.substringBefore('-').toInt()
                        val last = spec.substringAfter('-', (data.size - 1).toString()).toInt()
                        data.copyOfRange(first, last + 1)
                    }
                    ex.responseHeaders.add("Content-Type", "application/octet-stream")
                    if (range == null) {
                        ex.sendResponseHeaders(200, slice.size.toLong())
                    } else {
                        // What a real object store answers for a ranged GET, and
                        // the answer is bytes rather than the whole object.
                        ex.sendResponseHeaders(206, slice.size.toLong())
                    }
                    ex.responseBody.use { it.write(slice) }
                }
                "HEAD" -> ex.sendResponseHeaders(if (objects.containsKey(key)) 200 else 404, -1)
                "DELETE" -> { objects.remove(key); ex.sendResponseHeaders(204, -1) }
                else -> ex.sendResponseHeaders(405, -1)
            }
        } catch (t: Throwable) {
            runCatching { ex.sendResponseHeaders(500, -1) }
        } finally {
            ex.close()
        }
    }

    /** Recomputes the full Authorization header from what the wire carried. */
    private fun verifyAuth(ex: HttpExchange, payloadHash: String): Boolean {
        val auth = ex.requestHeaders.getFirst("Authorization") ?: return false
        val amzDate = ex.requestHeaders.getFirst("x-amz-date") ?: return false
        val sentHash = ex.requestHeaders.getFirst("x-amz-content-sha256") ?: return false
        // The PUT path must carry the real digest; GET/HEAD/DELETE the empty one.
        if (sentHash != payloadHash) return false
        val host = ex.requestHeaders.getFirst("Host") ?: return false
        val query = ex.requestURI.rawQuery?.takeIf { it.isNotEmpty() }
            ?.split('&')?.associate { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) pair to "" else pair.substring(0, eq) to pair.substring(eq + 1)
            } ?: emptyMap()
        val recomputed = SigV4.authorization(
            method = ex.requestMethod,
            canonicalUri = SigV4.canonicalUri(ex.requestURI.rawPath),
            canonicalQuery = SigV4.canonicalQuery(query),
            headers = buildMap {
                put("host", host)
                put("x-amz-date", amzDate)
                put("x-amz-content-sha256", sentHash)
                // A ranged GET signs the Range header too, so the fake has to
                // include it or the recomputed signature would not match.
                ex.requestHeaders.getFirst("range")?.let { put("range", it) }
            }.toMap(),
            payloadHash = sentHash,
            accessKey = accessKey,
            secretKey = secretKey,
            region = region,
            service = "s3",
            amzDate = amzDate,
            dateStamp = amzDate.substring(0, 8),
        )
        return recomputed == auth
    }

    @Test
    fun putGetExistsDeleteLifecycle() = runBlocking {
        val bytes = "hello s3".encodeToByteArray()
        assertFalse(provider.exists("blobs/ab/cd/ab123"))
        provider.put("blobs/ab/cd/ab123", ByteReadChannel(bytes))
        assertTrue(provider.exists("blobs/ab/cd/ab123"))
        assertEquals("hello s3", provider.get("blobs/ab/cd/ab123").readRemaining().readString())
        provider.delete("blobs/ab/cd/ab123")
        assertFalse(provider.exists("blobs/ab/cd/ab123"))
    }

    @Test
    fun putIsIdempotentLikeLocal() = runBlocking {
        provider.put("k", ByteReadChannel("first".encodeToByteArray()))
        // Second put with different bytes on an existing key: skipped, no PUT sent.
        provider.put("k", ByteReadChannel("second".encodeToByteArray()))
        assertEquals(1, putCount, "content-addressed blobs must never be overwritten")
        assertEquals("first", objects["k"]?.decodeToString())
    }

    @Test
    fun getMissingThrowsFileNotFound() = runBlocking {
        assertFailsWith<java.io.FileNotFoundException> { provider.get("nope") }
        Unit
    }

    @Test
    fun rangeReadAsksTheStoreForJustTheSlice() = runBlocking {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        provider.put("big", ByteReadChannel(bytes))
        lastRange = null
        val got = provider.getRange("big", 2048, 3048).readRemaining().readByteArray()
        // The prefix never crosses the wire: the request carries the range and
        // the store answers with the slice only.
        assertEquals("bytes=2048-3047", lastRange)
        assertContentEquals(bytes.copyOfRange(2048, 3048), got)
        lastRange = null
        assertContentEquals(bytes, provider.get("big").readRemaining().readByteArray())
        assertNull(lastRange, "a whole-object read must not send a range")
    }

    @Test
    fun rangeReadOfMissingKeyThrowsFileNotFound() = runBlocking {
        assertFailsWith<java.io.FileNotFoundException> { provider.getRange("nope", 0, 10) }
        Unit
    }

    @Test
    fun blobKeyMatchesLocalLayout() {
        val sha = "a".repeat(64)
        assertEquals("blobs/aa/aa/$sha", provider.blobKey(sha))
    }

    @Test
    fun largeStreamedPutUsesTempSpool() = runBlocking {
        val size = 3 shl 20
        val bytes = ByteArray(size) { (it % 251).toByte() }
        provider.put("big", ByteReadChannel(bytes))
        // verifyAuth already rejects a PUT whose x-amz-content-sha256 header
        // disagrees with the received body, so a stored blob means a spooled
        // stream that kept both its length and its digest intact.
        assertContentEquals(bytes, objects["big"])
    }
}
