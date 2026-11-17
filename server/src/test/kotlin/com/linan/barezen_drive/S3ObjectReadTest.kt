package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.s3.s3ObjectRow
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S3ObjectReadTest {
    private val storageDir = Files.createTempDirectory("bz-s3read").toString()
    private var accessKey = ""
    private var secret = ""
    private var userId = ""

    private suspend fun ApplicationTestBuilder.setup(maxFileSize: Long = 1L shl 30) {
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
        val bearer = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login)!!.groupValues[1]
        val created = client.post("/api/webdav/tokens") {
            header(HttpHeaders.Authorization, bearer)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"label":"s3","readOnly":false}""")
        }.bodyAsText()
        accessKey = Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1]
        secret = Regex(""""plaintext":"([^"]+)"""").find(created)!!.groupValues[1]
        userId = transaction(DatabaseFactory.db) {
            com.linan.barezen_drive.db.UsersTable.selectAll()
                .where { com.linan.barezen_drive.db.UsersTable.username eq "user1" }
                .single()[com.linan.barezen_drive.db.UsersTable.id].toString()
        }
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
    ): HttpResponse {
        val (authz, amzDate) = authFor(method, path, query, payloadHash)
        return client.request(if (query.isEmpty()) path else "$path?$query") {
            this.method = HttpMethod.parse(method)
            header(HttpHeaders.Authorization, authz)
            // 测试宿主不会替我们填 Host，而 host 是 SignedHeaders 里的一员：
            // 不显式发出去，服务端规范化出来的就是 "host:"，签名必然不符。
            header(HttpHeaders.Host, "localhost")
            header("x-amz-date", amzDate)
            header("x-amz-content-sha256", payloadHash)
            extraHeaders.forEach { (k, v) -> header(k, v) }
            if (body.isNotEmpty() || method == "PUT" || method == "POST") setBody(body)
        }
    }

    private suspend fun ApplicationTestBuilder.s3(
        method: String, path: String, query: String = "", body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse = s3(method, path, query, body.toByteArray(), extraHeaders)

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** 走真实 API 上传到指定文件夹，返回 file id。 */
    private suspend fun ApplicationTestBuilder.uploadViaApiTo(folderId: UUID?, name: String, body: String): String {
        val login = client.post("/api/auth/login") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"username":"user1","password":"password123"}""")
        }.bodyAsText()
        val bearer = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login)!!.groupValues[1]
        val bytes = body.toByteArray()
        val folderJson = folderId?.let { """"folderId":"$it",""" } ?: ""
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, bearer)
            header(HttpHeaders.ContentType, "application/json")
            setBody("{$folderJson\"name\":\"$name\",\"size\":${bytes.size}}")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val sid = Regex(""""uploadId":"([^"]+)"""").find(init.bodyAsText())!!.groupValues[1]
        client.put("/api/uploads/$sid/chunks/0") { header(HttpHeaders.Authorization, bearer); setBody(bytes) }
        val done = client.post("/api/uploads/$sid/complete") { header(HttpHeaders.Authorization, bearer) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return Regex(""""id":"([^"]+)"""").find(done.bodyAsText())!!.groupValues[1]
    }

    private fun bucketId(name: String): UUID = transaction(DatabaseFactory.db) {
        FoldersTable.selectAll().where { FoldersTable.name eq name }.single()[FoldersTable.id]
    }

    private suspend fun ApplicationTestBuilder.uploadViaApi(name: String, body: String): String =
        uploadViaApiTo(bucketId("testbucket"), name, body)

    private fun setMime(id: String, mime: String) {
        transaction(DatabaseFactory.db) {
            FilesTable.update({ FilesTable.id eq UUID.fromString(id) }) { it[FilesTable.mimeType] = mime }
        }
    }

    @Test
    fun getReturnsTheBytesEtagAndLastModified() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        uploadViaApi("a.txt", "hello s3")
        val r = s3("GET", "/testbucket/a.txt")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("hello s3", r.bodyAsText())
        val sha = sha256Hex("hello s3")
        assertEquals("\"$sha\"", r.headers[HttpHeaders.ETag])
        // 只断言"非空"测不出取的是哪一列：把 updated_at 换成 0 也一样非空。
        val updatedAt = transaction(DatabaseFactory.db) {
            FilesTable.selectAll().where { FilesTable.name eq "a.txt" }.single()[FilesTable.updatedAt]
        }
        val sent = ZonedDateTime.parse(r.headers[HttpHeaders.LastModified], DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli()
        assertEquals(updatedAt / 1000, sent / 1000)
        assertEquals("bytes", r.headers[HttpHeaders.AcceptRanges])
        assertNotNull(r.headers["x-amz-request-id"])
    }

    @Test
    fun headHasNoBodyAndMissingKeyIsEmpty404() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val ok = s3("HEAD", "/testbucket/nope")
        assertEquals(HttpStatusCode.NotFound, ok.status)
        assertEquals("", ok.bodyAsText())
        uploadViaApi("a.txt", "hello s3")
        val head = s3("HEAD", "/testbucket/a.txt")
        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals("", head.bodyAsText())
        assertEquals("8", head.headers[HttpHeaders.ContentLength])
        assertEquals("\"${sha256Hex("hello s3")}\"", head.headers[HttpHeaders.ETag])
    }

    @Test
    fun rangeIs206AndUnsatisfiableIs416() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        uploadViaApi("a.txt", "hello s3")
        val r = s3("GET", "/testbucket/a.txt", extraHeaders = mapOf("Range" to "bytes=0-4"))
        assertEquals(HttpStatusCode.PartialContent, r.status)
        assertEquals("hello", r.bodyAsText())
        assertEquals("bytes 0-4/8", r.headers[HttpHeaders.ContentRange])
        val bad = s3("GET", "/testbucket/a.txt", extraHeaders = mapOf("Range" to "bytes=99-100"))
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, bad.status)
    }

    @Test
    fun ifNoneMatchIs304AndIfMatchFailureIs412() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        uploadViaApi("a.txt", "hello s3")
        val etag = "\"${sha256Hex("hello s3")}\""
        val nm = s3("GET", "/testbucket/a.txt", extraHeaders = mapOf("If-None-Match" to etag))
        assertEquals(HttpStatusCode.NotModified, nm.status)
        assertEquals("", nm.bodyAsText())
        assertEquals(etag, nm.headers[HttpHeaders.ETag])
        val fail = s3("GET", "/testbucket/a.txt", extraHeaders = mapOf("If-Match" to "\"wrong\""))
        assertEquals(HttpStatusCode.PreconditionFailed, fail.status)
        assertTrue(fail.bodyAsText().contains("<Code>PreconditionFailed</Code>"), fail.bodyAsText())
        assertEquals(HttpStatusCode.OK, s3("GET", "/testbucket/a.txt", extraHeaders = mapOf("If-Match" to etag)).status)
    }

    @Test
    fun userMetadataIsEchoed() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val id = uploadViaApi("a.txt", "hello s3")
        transaction(DatabaseFactory.db) {
            FilesTable.update({ FilesTable.id eq UUID.fromString(id) }) {
                it[FilesTable.userMeta] = """{"mtime":"1700000000.000"}"""
            }
        }
        val r = s3("GET", "/testbucket/a.txt")
        assertEquals("1700000000.000", r.headers["x-amz-meta-mtime"])
        val head = s3("HEAD", "/testbucket/a.txt")
        assertEquals("1700000000.000", head.headers["x-amz-meta-mtime"])
    }

    @Test
    fun checksumModeReturnsSha256Base64() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        uploadViaApi("a.txt", "hello s3")
        val r = s3("GET", "/testbucket/a.txt", extraHeaders = mapOf("x-amz-checksum-mode" to "ENABLED"))
        val raw = MessageDigest.getInstance("SHA-256").digest("hello s3".toByteArray())
        assertEquals(Base64.getEncoder().encodeToString(raw), r.headers["x-amz-checksum-sha256"])
        // 没开 checksum-mode 就不该回：客户端会把它当成"服务端算过并校验过"。
        assertNull(s3("GET", "/testbucket/a.txt").headers["x-amz-checksum-sha256"])
    }

    @Test
    fun aTrashedFileIsNoSuchKey() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val id = uploadViaApi("a.txt", "hello s3")
        transaction(DatabaseFactory.db) {
            FilesTable.update({ FilesTable.id eq UUID.fromString(id) }) { it[FilesTable.deletedAt] = System.currentTimeMillis() }
        }
        val r = s3("GET", "/testbucket/a.txt")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NoSuchKey</Code>"), r.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, s3("HEAD", "/testbucket/a.txt").status)
    }

    @Test
    fun aMissingBucketIsNoSuchBucketNotNoSuchKey() = testApplication {
        setup()
        val r = s3("GET", "/nosuchbucket/a.txt")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NoSuchBucket</Code>"), r.bodyAsText())
    }

    @Test
    fun anOverlongKeySegmentIsInvalidArgument() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = s3("GET", "/testbucket/" + "a".repeat(300))
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Code>InvalidArgument</Code>"), r.bodyAsText())
    }

    @Test
    fun aFolderKeyIsNoSuchKey() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        transaction(DatabaseFactory.db) {
            FoldersTable.insert {
                it[FoldersTable.id] = UUID.randomUUID()
                it[FoldersTable.user] = UUID.fromString(userId)
                it[FoldersTable.parent] = bucketId("testbucket")
                it[FoldersTable.name] = "sub"
            }
        }
        val r = s3("GET", "/testbucket/sub")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NoSuchKey</Code>"), r.bodyAsText())
    }

    @Test
    fun scriptableMimeIsAttachmentAndMediaIsInline() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val html = uploadViaApi("a.html", "<html>x</html>")
        setMime(html, "text/html")
        val r = s3("GET", "/testbucket/a.html")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.headers[HttpHeaders.ContentDisposition]!!.startsWith("attachment"), r.headers.toString())
        val png = uploadViaApi("b.png", "not-really-a-png")
        setMime(png, "image/png")
        assertNull(s3("GET", "/testbucket/b.png").headers[HttpHeaders.ContentDisposition])
    }

    @Test
    fun objectSubresourcesAre501() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        uploadViaApi("a.txt", "hello s3")
        for (sub in listOf("acl", "tagging", "versionId", "torrent")) {
            val r = s3("GET", "/testbucket/a.txt", "$sub=")
            assertEquals(HttpStatusCode.NotImplemented, r.status, "$sub -> ${r.status}")
            assertTrue(r.bodyAsText().contains("<Code>NotImplemented</Code>"), r.bodyAsText())
        }
    }

    @Test
    fun aReservedBucketIsNeverTheSpaForObjectKeys() = testApplication {
        setup()
        for (name in listOf("api", "dav", "health", "readyz")) {
            val r = s3("GET", "/$name/a.txt")
            assertEquals(HttpStatusCode.NotFound, r.status, "$name -> ${r.status}")
            assertTrue(r.bodyAsText().contains("<Code>NoSuchBucket</Code>"), r.bodyAsText())
        }
    }

    @Test
    fun objectRowIsOwnerScopedAndSkipsTrashedFiles() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val id = uploadViaApi("a.txt", "hello s3")
        val uid = UUID.fromString(userId)
        val fid = UUID.fromString(id)
        assertNotNull(s3ObjectRow(uid, fid))
        assertNull(s3ObjectRow(UUID.randomUUID(), fid))
        transaction(DatabaseFactory.db) {
            FilesTable.update({ FilesTable.id eq fid }) { it[FilesTable.deletedAt] = System.currentTimeMillis() }
        }
        // resolveDavPath 已经过滤回收站，走 HTTP 永远测不到这一层；
        // 后续任务会直接按 id 调这个助手，所以必须单独钉住它自己的两个过滤条件。
        assertNull(s3ObjectRow(uid, fid))
    }

    /**
     * 按 S3 的预签名规则拼 URL：每个值百分号编码（Credential 里的 '/' 是 %2F），
     * 签名对"去掉 X-Amz-Signature 的原始 query"计算——正是 rclone 发出来的形状。
     */
    private fun presign(path: String, expires: Long = 900, at: Instant = Instant.now()): String {
        val amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(at)
        val dateStamp = amzDate.substring(0, 8)
        val scope = "$dateStamp/us-east-1/s3/aws4_request"
        val query = "X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Credential=" + SigV4.uriEncode("$accessKey/$scope") +
            "&X-Amz-Date=$amzDate&X-Amz-Expires=$expires&X-Amz-SignedHeaders=host"
        val canonical = SigV4.canonicalRequestRaw(
            "GET",
            SigV4.canonicalUriRaw(path),
            SigV4.canonicalQueryRaw(query),
            SigV4.canonicalHeaders(mapOf("host" to listOf("localhost")), listOf("host")),
            "host",
            "UNSIGNED-PAYLOAD",
        )
        val signature = SigV4.signatureHex(
            SigV4.signingKey(secret, dateStamp, "us-east-1", "s3"),
            SigV4.stringToSign(amzDate, scope, canonical),
        )
        return "$path?$query&X-Amz-Signature=$signature"
    }

    /** 预签名请求不带 Authorization；Host 仍要显式发，它参与了签名。 */
    private suspend fun ApplicationTestBuilder.presignedGet(url: String): HttpResponse =
        client.get(url) { header(HttpHeaders.Host, "localhost") }

    @Test
    fun aPresignedGetNeedsNoAuthorizationHeader() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        uploadViaApi("a.txt", "hello s3")
        val r = presignedGet(presign("/testbucket/a.txt"))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("hello s3", r.bodyAsText())
    }

    @Test
    fun anExpiredPresignedGetIsRequestExpired() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        uploadViaApi("a.txt", "hello s3")
        val r = presignedGet(presign("/testbucket/a.txt", expires = 1, at = Instant.now().minusSeconds(600)))
        assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Code>RequestExpired</Code>"), r.bodyAsText())
    }

    @Test
    fun aPresignedUrlWithATamperedSignatureIsRejected() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        uploadViaApi("a.txt", "hello s3")
        val url = presign("/testbucket/a.txt").replace(Regex("X-Amz-Signature=[0-9a-f]+"), "X-Amz-Signature=" + "0".repeat(64))
        val r = presignedGet(url)
        assertEquals(HttpStatusCode.Forbidden, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Code>SignatureDoesNotMatch</Code>"), r.bodyAsText())
    }
}