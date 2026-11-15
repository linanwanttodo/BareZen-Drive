package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.BlobDeleteQueueTable
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FileVersionsTable
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.files.BLOB_GRACE_MS
import com.linan.barezen_drive.files.processQueue
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.SigV4
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S3ObjectWriteTest {
    private val storageDir = Files.createTempDirectory("bz-s3write").toString()
    private var accessKey = ""
    private var secret = ""
    private var apiBearer = ""
    private var maxFileSize = 1L shl 30

    private suspend fun ApplicationTestBuilder.setup(maxFileSize: Long = 1L shl 30) {
        this@S3ObjectWriteTest.maxFileSize = maxFileSize
        val cfg = AppConfig(
            0, "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, maxFileSize, registrationOpen = true,
        )
        application { module(cfg, LocalStorageProvider(Path.of(cfg.storageDir))) }
        client.post("/api/auth/register") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"username":"user1","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"username":"user1","password":"password123"}""")
        }.bodyAsText()
        apiBearer = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login)!!.groupValues[1]
        val created = client.post("/api/webdav/tokens") {
            header(HttpHeaders.Authorization, apiBearer)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"label":"s3","readOnly":false}""")
        }.bodyAsText()
        accessKey = Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1]
        secret = Regex(""""plaintext":"([^"]+)"""").find(created)!!.groupValues[1]
    }

    /** 返回 (Authorization, x-amz-date)。两者必须来自同一个 Instant，否则签名对不上。 */
    private fun authFor(
        method: String, path: String, query: String = "",
        payloadHash: String = "UNSIGNED-PAYLOAD",
    ): Pair<String, String> {
        val amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
        val headers = linkedMapOf("host" to "localhost", "x-amz-date" to amzDate, "x-amz-content-sha256" to payloadHash)
        val authz = SigV4.authorization(
            method, SigV4.canonicalUriRaw(path), SigV4.canonicalQueryRaw(query),
            headers, payloadHash, accessKey, secret, "us-east-1", "s3", amzDate, amzDate.substring(0, 8),
        )
        return authz to amzDate
    }

    /** extraHeaders 不参与签名（SignedHeaders 只含 host/x-amz-date/x-amz-content-sha256）。 */
    private suspend fun ApplicationTestBuilder.s3(
        method: String, path: String, query: String = "",
        body: ByteArray = ByteArray(0),
        extraHeaders: Map<String, String> = emptyMap(),
        payloadHash: String = "UNSIGNED-PAYLOAD",
        noLength: Boolean = false,
    ): HttpResponse {
        val (authz, amzDate) = authFor(method, path, query, payloadHash)
        return sendS3(method, path, query, authz, amzDate, body, extraHeaders, payloadHash, noLength)
    }

    private suspend fun ApplicationTestBuilder.s3(
        method: String, path: String, query: String = "", body: String,
        extraHeaders: Map<String, String> = emptyMap(),
        payloadHash: String = "UNSIGNED-PAYLOAD",
        noLength: Boolean = false,
    ): HttpResponse = s3(method, path, query, body.toByteArray(), extraHeaders, payloadHash, noLength)

    private suspend fun ApplicationTestBuilder.sendS3(
        method: String, path: String, query: String, authz: String, amzDate: String,
        body: ByteArray, extraHeaders: Map<String, String>, payloadHash: String, noLength: Boolean,
    ): HttpResponse = client.request(if (query.isEmpty()) path else "$path?$query") {
        this.method = HttpMethod.parse(method)
        header(HttpHeaders.Authorization, authz)
        // 测试宿主不会替我们填 Host，而 host 是 SignedHeaders 里的一员：
        // 不显式发出去，服务端规范化出来的就是 "host:"，签名必然不符。
        header(HttpHeaders.Host, "localhost")
        header("x-amz-date", amzDate)
        header("x-amz-content-sha256", payloadHash)
        extraHeaders.forEach { (k, v) -> header(k, v) }
        if (noLength) {
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.OctetStream
                override val contentLength: Long? = null
                override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(body) }
            })
        } else if (body.isNotEmpty() || method == "PUT" || method == "POST") {
            setBody(body)
        }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun sha256Hex(s: String): String = hex(sha256(s.toByteArray()))

    private fun crc32B64(s: String): String {
        val crc = CRC32().apply { update(s.toByteArray()) }
        return Base64.getEncoder().encodeToString(
            byteArrayOf((crc.value ushr 24).toByte(), (crc.value ushr 16).toByte(), (crc.value ushr 8).toByte(), crc.value.toByte()),
        )
    }

    private fun md5B64(s: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(s.toByteArray()))

    private fun fileCount(): Long = transaction(DatabaseFactory.db) { FilesTable.selectAll().count() }

    /** 走真实 API 上传到指定文件夹，返回 file id。 */
    private suspend fun ApplicationTestBuilder.uploadViaApiTo(folderId: UUID?, name: String, body: String): String {
        val bytes = body.toByteArray()
        val folderJson = folderId?.let { """"folderId":"$it",""" } ?: ""
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, apiBearer)
            header(HttpHeaders.ContentType, "application/json")
            setBody("{$folderJson\"name\":\"$name\",\"size\":${bytes.size}}")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val sid = Regex(""""uploadId":"([^"]+)"""").find(init.bodyAsText())!!.groupValues[1]
        client.put("/api/uploads/$sid/chunks/0") { header(HttpHeaders.Authorization, apiBearer); setBody(bytes) }
        val done = client.post("/api/uploads/$sid/complete") { header(HttpHeaders.Authorization, apiBearer) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return Regex(""""id":"([^"]+)"""").find(done.bodyAsText())!!.groupValues[1]
    }

    /** 换一枚只读令牌来签后续请求；助手形状不变，只换字段。 */
    private suspend fun ApplicationTestBuilder.switchToReadOnlyToken() {
        val created = client.post("/api/webdav/tokens") {
            header(HttpHeaders.Authorization, apiBearer)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"label":"ro","readOnly":true}""")
        }.bodyAsText()
        accessKey = Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1]
        secret = Regex(""""plaintext":"([^"]+)"""").find(created)!!.groupValues[1]
    }

    // ---- aws-chunked 分帧：签名链用 javax.crypto 独立算，不复用被测的 SigV4 ----

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

    private fun streamKey(dateStamp: String): ByteArray =
        hmac(hmac(hmac(hmac("AWS4$secret".toByteArray(), dateStamp), "us-east-1"), "s3"), "aws4_request")

    private fun chunkSig(key: ByteArray, amzDate: String, scope: String, prev: String, dataHash: String): String =
        hex(hmac(key, listOf("AWS4-HMAC-SHA256-PAYLOAD", amzDate, scope, prev, hex(sha256(ByteArray(0))), dataHash).joinToString("\n")))

    private fun framed(parts: List<ByteArray>, amzDate: String?, seed: String?, trailer: Pair<String, String>?): ByteArray {
        val out = ByteArrayOutputStream()
        fun line(s: String) = out.write(s.toByteArray())
        val signed = amzDate != null
        val key = if (signed) streamKey(amzDate!!.substring(0, 8)) else ByteArray(0)
        val scope = "${amzDate?.substring(0, 8)}/us-east-1/s3/aws4_request"
        var prev = seed ?: ""
        for (p in parts) {
            val sig = if (signed) chunkSig(key, amzDate!!, scope, prev, hex(sha256(p))) else null
            line(Integer.toHexString(p.size) + (sig?.let { ";chunk-signature=$it" } ?: "") + "\r\n")
            out.write(p)
            line("\r\n")
            if (sig != null) prev = sig
        }
        val zeroSig = if (signed) chunkSig(key, amzDate!!, scope, prev, hex(sha256(ByteArray(0)))) else null
        line("0" + (zeroSig?.let { ";chunk-signature=$it" } ?: "") + "\r\n")
        if (zeroSig != null) prev = zeroSig
        if (trailer != null) {
            val canonical = "${trailer.first}:${trailer.second}\n"
            line(canonical)
            if (signed) {
                val tsig = hex(
                    hmac(key, listOf("AWS4-HMAC-SHA256-TRAILER", amzDate!!, scope, prev, hex(sha256(canonical.toByteArray()))).joinToString("\n")),
                )
                line("x-amz-trailer-signature:$tsig\r\n")
            }
        }
        line("\r\n")
        return out.toByteArray()
    }

    private fun seedOf(authz: String): String = Regex("Signature=([0-9a-f]+)").find(authz)!!.groupValues[1]

    // ---- 基本读写 ----

    @Test
    fun putThenGetRoundTrips() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = s3("PUT", "/testbucket/a.txt", body = "hello s3")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("\"${sha256Hex("hello s3")}\"", r.headers[HttpHeaders.ETag])
        assertEquals("hello s3", s3("GET", "/testbucket/a.txt").bodyAsText())
    }

    @Test
    fun anEmptyObjectRoundTrips() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = s3("PUT", "/testbucket/empty.txt", body = ByteArray(0))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("\"${sha256Hex("")}\"", r.headers[HttpHeaders.ETag])
        val got = s3("GET", "/testbucket/empty.txt")
        assertEquals(HttpStatusCode.OK, got.status)
        assertEquals("", got.bodyAsText())
    }

    @Test
    fun putCreatesImplicitParents() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        assertEquals(HttpStatusCode.OK, s3("PUT", "/testbucket/a/b/c.txt", body = "x").status)
        assertEquals("x", s3("GET", "/testbucket/a/b/c.txt").bodyAsText())
    }

    @Test
    fun putToAMissingBucketIs404() = testApplication {
        setup()
        val r = s3("PUT", "/nobucket/a.txt", body = "x")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NoSuchBucket</Code>"), r.bodyAsText())
        assertEquals(0L, fileCount())
    }

    @Test
    fun overwriteDoesNotCreateAVersion() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "one")
        s3("PUT", "/testbucket/a.txt", body = "two")
        assertEquals("two", s3("GET", "/testbucket/a.txt").bodyAsText())
        assertEquals(0L, transaction(DatabaseFactory.db) { FileVersionsTable.selectAll().count() })
    }

    @Test
    fun aKeyNamingAFolderIs409() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a/b.txt", body = "x")
        val r = s3("PUT", "/testbucket/a", body = "y")
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidRequest</Code>"), r.bodyAsText())
    }

    @Test
    fun aFileInThePathIs409() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a", body = "x")
        assertEquals(HttpStatusCode.Conflict, s3("PUT", "/testbucket/a/b.txt", body = "y").status)
    }

    @Test
    fun missingLengthIs411() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = s3("PUT", "/testbucket/a/b.txt", body = "x", noLength = true)
        assertEquals(HttpStatusCode.LengthRequired, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Code>MissingContentLength</Code>"), r.bodyAsText())
        assertEquals(0L, fileCount())
        assertEquals(1L, transaction(DatabaseFactory.db) { FoldersTable.selectAll().count() })
    }

    @Test
    fun overTheServerCapIsEntityTooLarge() = testApplication {
        setup(maxFileSize = 4)
        s3("PUT", "/testbucket")
        val r = s3("PUT", "/testbucket/a/b.txt", body = "12345")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>EntityTooLarge</Code>"), r.bodyAsText())
        assertEquals(0L, fileCount())
        assertEquals(1L, transaction(DatabaseFactory.db) { FoldersTable.selectAll().count() })
    }

    @Test
    fun copySourceIsNotImplementedYet() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = s3("PUT", "/testbucket/a.txt", extraHeaders = mapOf("x-amz-copy-source" to "/testbucket/b.txt"))
        assertEquals(HttpStatusCode.NotImplemented, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NotImplemented</Code>"), r.bodyAsText())
    }

    @Test
    fun sigV4AIsNotImplemented() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = s3(
            "PUT", "/testbucket/a.txt", body = "x",
            payloadHash = "STREAMING-AWS4-ECDSA-P256-SHA256-PAYLOAD",
        )
        assertEquals(HttpStatusCode.NotImplemented, r.status)
    }

    @Test
    fun readOnlyTokenCannotWrite() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        switchToReadOnlyToken()
        val r = s3("PUT", "/testbucket/a.txt", body = "x")
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("<Code>AccessDenied</Code>"), r.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, s3("DELETE", "/testbucket/a.txt").status)
    }

    // ---- 秒传与校验和的先后（数据丢失高发点）----

    @Test
    fun instantUploadDrainsTheBodyAndReturnsTheEtag() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/first.txt", body = "same bytes")
        val sha = sha256Hex("same bytes")
        val r = s3("PUT", "/testbucket/second.txt", body = "same bytes", payloadHash = sha)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("\"$sha\"", r.headers[HttpHeaders.ETag])
        assertEquals("same bytes", s3("GET", "/testbucket/second.txt").bodyAsText())
    }

    @Test
    fun aBadInstantChecksumPublishesNothingAndKeepsTheSharedBlob() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/first.txt", body = "same bytes")
        val before = fileCount()
        val r = s3(
            "PUT", "/testbucket/second.txt", body = "same bytes",
            payloadHash = sha256Hex("same bytes"), extraHeaders = mapOf("Content-MD5" to "AAAA"),
        )
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>BadDigest</Code>"), r.bodyAsText())
        assertEquals(before, fileCount())
        // 秒传行共享的是 first.txt 的 blob：删行不能把字节一起删了
        assertEquals("same bytes", s3("GET", "/testbucket/first.txt").bodyAsText())
    }

    @Test
    fun aBadInstantPayloadHashPublishesNothing() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/first.txt", body = "same bytes")
        val before = fileCount()
        val r = s3(
            "PUT", "/testbucket/second.txt", body = "same bytes",
            payloadHash = sha256Hex("same bytes"), extraHeaders = mapOf("x-amz-checksum-crc32" to "AAAAAA=="),
        )
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>BadDigest</Code>"), r.bodyAsText())
        assertEquals(before, fileCount())
        assertEquals("same bytes", s3("GET", "/testbucket/first.txt").bodyAsText())
    }

    @Test
    fun anOverwriteWithABadChecksumKeepsTheOldBytes() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "one")
        // 让 "two" 的 blob 先存在：若覆盖时把 sha256 交给 initUpload，就会走秒传覆盖，
        // 在校验和核对之前把 a.txt 换成新内容，失败后旧字节已经没了。
        s3("PUT", "/testbucket/other.txt", body = "two")
        val r = s3(
            "PUT", "/testbucket/a.txt", body = "two",
            payloadHash = sha256Hex("two"), extraHeaders = mapOf("Content-MD5" to "AAAA"),
        )
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>BadDigest</Code>"), r.bodyAsText())
        assertEquals("one", s3("GET", "/testbucket/a.txt").bodyAsText())
        assertEquals("two", s3("GET", "/testbucket/other.txt").bodyAsText())
        assertEquals(0L, transaction(DatabaseFactory.db) { FileVersionsTable.selectAll().count() })
    }

    @Test
    fun aWrongPayloadHashIsRejectedBeforePublish() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = s3("PUT", "/testbucket/a.txt", body = "hello", payloadHash = sha256Hex("not hello"))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>XAmzContentSHA256Mismatch</Code>"), r.bodyAsText())
        assertEquals(0L, fileCount())
    }

    @Test
    fun aBadContentMd5PublishesNothing() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val before = fileCount()
        val r = s3("PUT", "/testbucket/a.txt", body = "hello", extraHeaders = mapOf("Content-MD5" to "AAAA"))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>BadDigest</Code>"), r.bodyAsText())
        assertEquals(before, fileCount())
    }

    @Test
    fun aGoodContentMd5IsAccepted() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = s3("PUT", "/testbucket/a.txt", body = "hello", extraHeaders = mapOf("Content-MD5" to md5B64("hello")))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    }

    @Test
    fun checksumsAreVerifiedAndEchoed() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val b64 = crc32B64("hello")
        val r = s3("PUT", "/testbucket/a.txt", body = "hello", extraHeaders = mapOf("x-amz-checksum-crc32" to b64))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(b64, r.headers["x-amz-checksum-crc32"])
        val bad = s3("PUT", "/testbucket/b.txt", body = "hello", extraHeaders = mapOf("x-amz-checksum-crc32" to "AAAAAA=="))
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(bad.bodyAsText().contains("<Code>BadDigest</Code>"), bad.bodyAsText())
        assertEquals(1L, fileCount())
    }

    @Test
    fun metadataRoundTripsAndOverwriteClearsIt() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "one", extraHeaders = mapOf("x-amz-meta-mtime" to "1.0"))
        assertEquals("1.0", s3("GET", "/testbucket/a.txt").headers["x-amz-meta-mtime"])
        s3("PUT", "/testbucket/a.txt", body = "two")
        assertNull(s3("GET", "/testbucket/a.txt").headers["x-amz-meta-mtime"])
    }

    // ---- aws-chunked ----

    @Test
    fun unsignedTrailerStreamingPutVerifiesTheTrailerChecksum() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val b64 = crc32B64("hello world")
        val body = framed(
            listOf("hello ".toByteArray(), "world".toByteArray()), null, null,
            "x-amz-checksum-crc32" to b64,
        )
        val r = s3(
            "PUT", "/testbucket/a.txt", body = body,
            payloadHash = "STREAMING-UNSIGNED-PAYLOAD-TRAILER",
            extraHeaders = mapOf("x-amz-decoded-content-length" to "11", "x-amz-trailer" to "x-amz-checksum-crc32"),
        )
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("\"${sha256Hex("hello world")}\"", r.headers[HttpHeaders.ETag])
        assertEquals(b64, r.headers["x-amz-checksum-crc32"])
        assertEquals("hello world", s3("GET", "/testbucket/a.txt").bodyAsText())
    }

    @Test
    fun aBadTrailerChecksumPublishesNothing() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val body = framed(
            listOf("hello world".toByteArray()), null, null,
            "x-amz-checksum-crc32" to "AAAAAA==",
        )
        val r = s3(
            "PUT", "/testbucket/a.txt", body = body,
            payloadHash = "STREAMING-UNSIGNED-PAYLOAD-TRAILER",
            extraHeaders = mapOf("x-amz-decoded-content-length" to "11"),
        )
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Code>BadDigest</Code>"), r.bodyAsText())
        assertEquals(0L, fileCount())
    }

    @Test
    fun signedStreamingPutVerifiesChunkSignatures() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val payloadHash = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER"
        val (authz, amzDate) = authFor("PUT", "/testbucket/a.txt", payloadHash = payloadHash)
        val b64 = crc32B64("hello world")
        val body = framed(
            listOf("hello ".toByteArray(), "world".toByteArray()), amzDate, seedOf(authz),
            "x-amz-checksum-crc32" to b64,
        )
        val r = sendS3(
            "PUT", "/testbucket/a.txt", "", authz, amzDate, body,
            mapOf("x-amz-decoded-content-length" to "11", "x-amz-trailer" to "x-amz-checksum-crc32"),
            payloadHash, false,
        )
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("hello world", s3("GET", "/testbucket/a.txt").bodyAsText())
    }

    @Test
    fun aTamperedChunkSignaturePublishesNothing() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val payloadHash = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD"
        val (authz, amzDate) = authFor("PUT", "/testbucket/a.txt", payloadHash = payloadHash)
        val good = framed(listOf("hello world".toByteArray()), amzDate, seedOf(authz), null)
        // 只有一个块：数据全部交给下游之后才校验签名，所以错误必须靠读到 EOF 才暴露。
        val sig = Regex(";chunk-signature=([0-9a-f]{64})").find(String(good))!!.groupValues[1]
        val bad = String(good).replaceFirst(sig, if (sig[0] == '0') "1" + sig.substring(1) else "0" + sig.substring(1))
        val r = sendS3(
            "PUT", "/testbucket/a.txt", "", authz, amzDate, bad.toByteArray(),
            mapOf("x-amz-decoded-content-length" to "11"), payloadHash, false,
        )
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Code>XAmzContentSHA256Mismatch</Code>"), r.bodyAsText())
        assertEquals(0L, fileCount())
    }

    @Test
    fun aTamperedSignatureOnALargeChunkPublishesNothing() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val payloadHash = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD"
        val (authz, amzDate) = authFor("PUT", "/testbucket/big.bin", payloadHash = payloadHash)
        // 块大到解码器必须分多次 writeFully、被背压挂住：消费方会先把 declared 字节
        // 全部取走并返回，签名错误只能在读到 EOF 时才暴露。小块时解码器往往抢先
        // 取消通道，那条路径测不出这个守卫。
        val data = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val good = framed(listOf(data), amzDate, seedOf(authz), null)
        val sig = Regex(";chunk-signature=([0-9a-f]{64})").find(String(good, Charsets.ISO_8859_1))!!.groupValues[1]
        val bad = String(good, Charsets.ISO_8859_1)
            .replaceFirst(sig, if (sig[0] == '0') "1" + sig.substring(1) else "0" + sig.substring(1))
            .toByteArray(Charsets.ISO_8859_1)
        val r = sendS3(
            "PUT", "/testbucket/big.bin", "", authz, amzDate, bad,
            mapOf("x-amz-decoded-content-length" to data.size.toString()), payloadHash, false,
        )
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Code>XAmzContentSHA256Mismatch</Code>"), r.bodyAsText())
        assertEquals(0L, fileCount())
    }

    @Test
    fun aDecodedLengthThatDoesNotMatchPublishesNothing() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val body = framed(listOf("hello world".toByteArray()), null, null, null)
        val r = s3(
            "PUT", "/testbucket/a.txt", body = body,
            payloadHash = "STREAMING-UNSIGNED-PAYLOAD-TRAILER",
            extraHeaders = mapOf("x-amz-decoded-content-length" to "12"),
        )
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertEquals(0L, fileCount())
    }

    // ---- DELETE ----

    @Test
    fun deleteIsIdempotentAndHard() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a/b.txt", body = "x")
        assertEquals(HttpStatusCode.NoContent, s3("DELETE", "/testbucket/a/b.txt").status)
        assertEquals(HttpStatusCode.NoContent, s3("DELETE", "/testbucket/a/b.txt").status)
        assertEquals(HttpStatusCode.NotFound, s3("GET", "/testbucket/a/b.txt").status)
        assertEquals(0L, transaction(DatabaseFactory.db) {
            FilesTable.selectAll().where { FilesTable.deletedAt greater 0L }.count()
        })
        assertEquals(1L, transaction(DatabaseFactory.db) { FoldersTable.selectAll().count() })
        assertEquals(HttpStatusCode.OK, s3("HEAD", "/testbucket").status)
        assertEquals(HttpStatusCode.NoContent, s3("DELETE", "/testbucket").status)
        assertEquals(HttpStatusCode.NotFound, s3("HEAD", "/testbucket").status)
    }

    @Test
    fun deleteOfAFolderKeyIsNoContent() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a/b.txt", body = "x")
        assertEquals(HttpStatusCode.NoContent, s3("DELETE", "/testbucket/a").status)
        assertEquals("x", s3("GET", "/testbucket/a/b.txt").bodyAsText())
    }

    @Test
    fun deleteQueuesTheBlobAndTheSweepUnlinksIt() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "unique bytes here")
        val sha = sha256Hex("unique bytes here")
        val blob = Path.of(storageDir, "blobs", sha.substring(0, 2), sha.substring(2, 4), sha)
        assertTrue(Files.exists(blob), "blob should exist at $blob")
        assertEquals(HttpStatusCode.NoContent, s3("DELETE", "/testbucket/a.txt").status)
        // 宽限期内只入队，不动字节
        assertTrue(Files.exists(blob), "blob must survive the grace window")
        assertEquals(1L, transaction(DatabaseFactory.db) { BlobDeleteQueueTable.selectAll().count() })
        processQueue(LocalStorageProvider(Path.of(storageDir)), now = System.currentTimeMillis() + BLOB_GRACE_MS + 1)
        assertTrue(!Files.exists(blob), "blob should be unlinked at $blob")
    }

    @Test
    fun deleteKeepsABlobAnotherNameStillReferences() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "shared bytes")
        s3("PUT", "/testbucket/b.txt", body = "shared bytes", payloadHash = sha256Hex("shared bytes"))
        assertEquals(HttpStatusCode.NoContent, s3("DELETE", "/testbucket/a.txt").status)
        assertEquals("shared bytes", s3("GET", "/testbucket/b.txt").bodyAsText())
    }

    @Test
    fun deleteOfAMissingBucketIs404() = testApplication {
        setup()
        val r = s3("DELETE", "/nobucket/a.txt")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NoSuchBucket</Code>"), r.bodyAsText())
    }

    @Test
    fun aPutViaApiIsVisibleToS3AndViceVersa() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val bucket = transaction(DatabaseFactory.db) {
            FoldersTable.selectAll().where { FoldersTable.name eq "testbucket" }.single()[FoldersTable.id]
        }
        uploadViaApiTo(bucket, "from-api.txt", "api bytes")
        assertEquals("api bytes", s3("GET", "/testbucket/from-api.txt").bodyAsText())
        s3("PUT", "/testbucket/from-s3.txt", body = "s3 bytes")
        val listing = client.get("/api/folders/$bucket/contents") { header(HttpHeaders.Authorization, apiBearer) }
        assertEquals(HttpStatusCode.OK, listing.status)
        assertTrue(listing.bodyAsText().contains("from-s3.txt"), listing.bodyAsText())
    }
}