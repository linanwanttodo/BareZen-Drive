package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FileVersionsTable
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.UploadChunksTable
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.files.UploadService
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.SigV4
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class S3MultipartTest {
    private val storageDir = Files.createTempDirectory("bz-s3mpu").toString()
    private var accessKey = ""
    private var secret = ""
    private var apiBearer = ""

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
        apiBearer = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login)!!.groupValues[1]
        val created = client.post("/api/webdav/tokens") {
            header(HttpHeaders.Authorization, apiBearer)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"label":"s3","readOnly":false}""")
        }.bodyAsText()
        accessKey = Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1]
        secret = Regex(""""plaintext":"([^"]+)"""").find(created)!!.groupValues[1]
    }

    private fun authFor(method: String, path: String, query: String = ""): Pair<String, String> {
        val amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
        val headers = linkedMapOf("host" to "localhost", "x-amz-date" to amzDate, "x-amz-content-sha256" to "UNSIGNED-PAYLOAD")
        val authz = SigV4.authorization(
            method, SigV4.canonicalUriRaw(path), SigV4.canonicalQueryRaw(query),
            headers, "UNSIGNED-PAYLOAD", accessKey, secret, "us-east-1", "s3", amzDate, amzDate.substring(0, 8),
        )
        return authz to amzDate
    }

    private suspend fun ApplicationTestBuilder.s3(
        method: String, path: String, query: String = "",
        body: ByteArray = ByteArray(0), extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val (authz, amzDate) = authFor(method, path, query)
        return client.request(if (query.isEmpty()) path else "$path?$query") {
            this.method = HttpMethod.parse(method)
            header(HttpHeaders.Authorization, authz)
            header(HttpHeaders.Host, "localhost")
            header("x-amz-date", amzDate)
            header("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
            extraHeaders.forEach { (k, v) -> header(k, v) }
            if (body.isNotEmpty() || method == "PUT" || method == "POST") setBody(body)
        }
    }

    private suspend fun ApplicationTestBuilder.s3(
        method: String, path: String, query: String = "", body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse = s3(method, path, query, body.toByteArray(), extraHeaders)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun crc32Raw(bytes: ByteArray): ByteArray {
        val c = CRC32()
        c.update(bytes)
        val v = c.value
        return byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }

    /** S3 的复合校验和：各 part 的二进制摘要串起来再取一次 CRC32，base64 后加 -N。 */
    private fun crc32Composite(parts: List<ByteArray>): String {
        val c = CRC32()
        parts.forEach { c.update(it) }
        val v = c.value
        val raw = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        return Base64.getEncoder().encodeToString(raw) + "-" + parts.size
    }

    private fun fileCount(): Long = transaction(DatabaseFactory.db) { FilesTable.selectAll().count() }

    private fun partDir(uploadId: String): Path = Path.of(storageDir, "tmp", uploadId, "mpu")

    private suspend fun ApplicationTestBuilder.createMpu(key: String, meta: Map<String, String> = emptyMap()): String {
        val r = s3("POST", "/testbucket/$key", "uploads=", extraHeaders = meta)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return Regex("<UploadId>(.*?)</UploadId>").find(r.bodyAsText())!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.uploadPart(
        key: String, uploadId: String, n: Int, bytes: ByteArray, extra: Map<String, String> = emptyMap(),
    ): String {
        val r = s3("PUT", "/testbucket/$key", "partNumber=$n&uploadId=$uploadId", body = bytes, extraHeaders = extra)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return r.headers[HttpHeaders.ETag]!!.trim('"')
    }

    private fun completeBody(parts: List<Pair<Int, String>>) = buildString {
        append("<CompleteMultipartUpload>")
        parts.forEach { (n, etag) -> append("<Part><PartNumber>$n</PartNumber><ETag>&quot;$etag&quot;</ETag></Part>") }
        append("</CompleteMultipartUpload>")
    }

    private suspend fun ApplicationTestBuilder.switchToReadOnlyToken() {
        val created = client.post("/api/webdav/tokens") {
            header(HttpHeaders.Authorization, apiBearer)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"label":"ro","readOnly":true}""")
        }.bodyAsText()
        accessKey = Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1]
        secret = Regex(""""plaintext":"([^"]+)"""").find(created)!!.groupValues[1]
    }

    @Test
    fun createUploadCompleteRoundTrips() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin", mapOf("x-amz-meta-mtime" to "1.0"))
        val a = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        val b = "tail".toByteArray()
        val e1 = uploadPart("big.bin", uid, 1, a)
        val e2 = uploadPart("big.bin", uid, 2, b)
        val r = s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1, 2 to e2)))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("CompleteMultipartUploadResult"), r.bodyAsText())
        val got = s3("GET", "/testbucket/big.bin")
        assertEquals(HttpStatusCode.OK, got.status)
        assertTrue((a + b).contentEquals(got.bodyAsBytes()), "合并后的字节不一致")
        assertEquals("1.0", got.headers["x-amz-meta-mtime"])
        // 整文件 ETag 是 sha256，不是 S3 的 md5-of-md5s-N
        assertEquals(sha256Hex(a + b), Regex("<ETag>&quot;(.*?)&quot;</ETag>").find(r.bodyAsText())!!.groupValues[1])
        assertEquals(1L, fileCount())
    }

    @Test
    fun partOrderMustBeAscending() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val e1 = uploadPart("big.bin", uid, 1, ByteArray(5 * 1024 * 1024) { 1 })
        val e2 = uploadPart("big.bin", uid, 2, "tail".toByteArray())
        val r = s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(2 to e2, 1 to e1)))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidPartOrder</Code>"), r.bodyAsText())
        assertEquals(0L, fileCount())
    }

    @Test
    fun aDuplicatePartNumberIsInvalidPartOrder() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val e1 = uploadPart("big.bin", uid, 1, ByteArray(5 * 1024 * 1024) { 1 })
        val r = s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1, 1 to e1)))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidPartOrder</Code>"), r.bodyAsText())
    }

    @Test
    fun aWrongEtagIsInvalidPart() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        uploadPart("big.bin", uid, 1, ByteArray(5 * 1024 * 1024) { 1 })
        val r = s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to "0".repeat(64))))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidPart</Code>"), r.bodyAsText())
        assertEquals(0L, fileCount())
    }

    @Test
    fun aTooSmallNonLastPartIsEntityTooSmall() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val e1 = uploadPart("big.bin", uid, 1, "small".toByteArray())
        val e2 = uploadPart("big.bin", uid, 2, "tail".toByteArray())
        val r = s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1, 2 to e2)))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>EntityTooSmall</Code>"), r.bodyAsText())
    }

    @Test
    fun aSingleSmallPartIsAccepted() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("one.bin")
        val e1 = uploadPart("one.bin", uid, 1, "just me".toByteArray())
        val r = s3("POST", "/testbucket/one.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1)))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("just me", s3("GET", "/testbucket/one.bin").bodyAsText())
    }

    @Test
    fun abortMakesTheUploadIdDisappear() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        uploadPart("big.bin", uid, 1, ByteArray(5 * 1024 * 1024) { 7 })
        assertEquals(HttpStatusCode.NoContent, s3("DELETE", "/testbucket/big.bin", "uploadId=$uid").status)
        assertEquals(HttpStatusCode.NotFound, s3("DELETE", "/testbucket/big.bin", "uploadId=$uid").status)
        val r = s3("PUT", "/testbucket/big.bin", "partNumber=1&uploadId=$uid", body = "x")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NoSuchUpload</Code>"), r.bodyAsText())
    }

    @Test
    fun abortRemovesTheSessionRowChunksAndStaging() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        uploadPart("big.bin", uid, 1, ByteArray(5 * 1024 * 1024) { 3 })
        val sid = UUID.fromString(uid)
        assertTrue(Files.exists(partDir(uid).resolve("1.part")), "暂存片本该在")
        assertEquals(HttpStatusCode.NoContent, s3("DELETE", "/testbucket/big.bin", "uploadId=$uid").status)
        assertEquals(0L, transaction(DatabaseFactory.db) {
            UploadSessionsTable.selectAll().where { UploadSessionsTable.id eq sid }.count()
        })
        assertEquals(0L, transaction(DatabaseFactory.db) {
            UploadChunksTable.selectAll().where { UploadChunksTable.session eq sid }.count()
        })
        assertFalse(Files.exists(partDir(uid).resolve("1.part")), "Abort 之后暂存必须没了")
    }

    @Test
    fun abortKeepsTheObjectThatWasAlreadyThere() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "old bytes")
        val uid = createMpu("a.txt")
        uploadPart("a.txt", uid, 1, ByteArray(5 * 1024 * 1024) { 9 })
        assertEquals(HttpStatusCode.NoContent, s3("DELETE", "/testbucket/a.txt", "uploadId=$uid").status)
        assertEquals("old bytes", s3("GET", "/testbucket/a.txt").bodyAsText())
    }

    @Test
    fun anExpiredSessionIsNoSuchUpload() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        transaction(DatabaseFactory.db) {
            UploadSessionsTable.update({ UploadSessionsTable.id eq UUID.fromString(uid) }) {
                it[UploadSessionsTable.expiresAt] = System.currentTimeMillis() - 1000
            }
        }
        val r = s3("PUT", "/testbucket/big.bin", "partNumber=1&uploadId=$uid", body = "x")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NoSuchUpload</Code>"), r.bodyAsText())
    }

    @Test
    fun anOrdinaryUploadSessionIsNotAMultipartUpload() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val (bucket, owner) = transaction(DatabaseFactory.db) {
            val row = FoldersTable.selectAll().where { FoldersTable.name eq "testbucket" }.single()
            row[FoldersTable.id] to row[FoldersTable.user]
        }
        val sid = UUID.randomUUID()
        transaction(DatabaseFactory.db) {
            UploadSessionsTable.insert {
                it[UploadSessionsTable.id] = sid
                it[UploadSessionsTable.user] = owner
                it[UploadSessionsTable.folder] = bucket
                it[UploadSessionsTable.name] = "x.bin"
                it[UploadSessionsTable.size] = 1
                it[UploadSessionsTable.chunkSize] = 1
                it[UploadSessionsTable.status] = "open"
                it[UploadSessionsTable.expiresAt] = System.currentTimeMillis() + 3600_000
            }
        }
        val r = s3("POST", "/testbucket/x.bin", "uploadId=$sid", body = completeBody(listOf(1 to "0".repeat(64))))
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NoSuchUpload</Code>"), r.bodyAsText())
    }

    @Test
    fun eachPartRenewsTheSession() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        transaction(DatabaseFactory.db) {
            UploadSessionsTable.update({ UploadSessionsTable.id eq UUID.fromString(uid) }) {
                it[UploadSessionsTable.expiresAt] = System.currentTimeMillis() + 1000
            }
        }
        uploadPart("big.bin", uid, 1, ByteArray(5 * 1024 * 1024) { 1 })
        val renewed = transaction(DatabaseFactory.db) {
            UploadSessionsTable.selectAll().where { UploadSessionsTable.id eq UUID.fromString(uid) }
                .single()[UploadSessionsTable.expiresAt]
        }
        assertTrue(renewed > System.currentTimeMillis() + 23L * 3600 * 1000, "UploadPart 没有续期")
    }

    @Test
    fun unlistedPartsAreDiscarded() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val e1 = uploadPart("big.bin", uid, 1, ByteArray(5 * 1024 * 1024) { 1 })
        uploadPart("big.bin", uid, 2, "discard me".toByteArray())
        s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1)))
        assertEquals(5 * 1024 * 1024, s3("GET", "/testbucket/big.bin").bodyAsBytes().size)
        assertFalse(Files.exists(partDir(uid)), "合并后整个暂存目录都该被清掉")
    }

    @Test
    fun partNumberOutOfRangeIs400() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        assertEquals(
            HttpStatusCode.BadRequest,
            s3("PUT", "/testbucket/big.bin", "partNumber=0&uploadId=$uid", body = "x").status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            s3("PUT", "/testbucket/big.bin", "partNumber=10001&uploadId=$uid", body = "x").status,
        )
    }

    @Test
    fun aMissingStagingFileIsInvalidPartNotInvalidArgument() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val e1 = uploadPart("big.bin", uid, 1, ByteArray(5 * 1024 * 1024) { 1 })
        Files.delete(partDir(uid).resolve("1.part"))
        val r = s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1)))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidPart</Code>"), r.bodyAsText())
        assertEquals(0L, fileCount())
    }

    @Test
    fun reUploadingAPartNumberReplacesIt() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        uploadPart("big.bin", uid, 1, ByteArray(5 * 1024 * 1024) { 1 })
        val second = ByteArray(5 * 1024 * 1024) { 2 }
        val e1 = uploadPart("big.bin", uid, 1, second)
        val tail = "z".toByteArray()
        val e2 = uploadPart("big.bin", uid, 2, tail)
        val r = s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1, 2 to e2)))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(sha256Hex(second + tail), sha256Hex(s3("GET", "/testbucket/big.bin").bodyAsBytes()))
    }

    @Test
    fun aBadPartContentMd5PublishesNothing() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val r = s3(
            "PUT", "/testbucket/big.bin", "partNumber=1&uploadId=$uid",
            body = "hello".toByteArray(),
            extraHeaders = mapOf("Content-MD5" to Base64.getEncoder().encodeToString("not-the-md5".toByteArray())),
        )
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertEquals(0L, transaction(DatabaseFactory.db) { UploadChunksTable.selectAll().count() })
        assertFalse(Files.exists(partDir(uid).resolve("1.part")), "没核对过的分片不能留下")
    }

    @Test
    fun aBadPartChecksumPublishesNothingAndAGoodOneStillWorks() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val bytes = ByteArray(5 * 1024 * 1024) { (it % 7).toByte() }
        val wrong = ByteArray(5 * 1024 * 1024) { (it % 8).toByte() }
        val bad = s3(
            "PUT", "/testbucket/big.bin", "partNumber=1&uploadId=$uid", body = bytes,
            extraHeaders = mapOf("x-amz-checksum-crc32" to Base64.getEncoder().encodeToString(crc32Raw(wrong))),
        )
        assertEquals(HttpStatusCode.BadRequest, bad.status, bad.bodyAsText())
        assertEquals(0L, transaction(DatabaseFactory.db) { UploadChunksTable.selectAll().count() })
        val e1 = uploadPart(
            "big.bin", uid, 1, bytes,
            mapOf("x-amz-checksum-crc32" to Base64.getEncoder().encodeToString(crc32Raw(bytes))),
        )
        val tail = "t".toByteArray()
        val e2 = uploadPart("big.bin", uid, 2, tail)
        val r = s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1, 2 to e2)))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(bytes.size + tail.size, s3("GET", "/testbucket/big.bin").bodyAsBytes().size)
    }

    @Test
    fun theCompositeChecksumIsVerifiedOnComplete() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val a = ByteArray(5 * 1024 * 1024) { (it % 3).toByte() }
        val b = ByteArray(5 * 1024 * 1024) { (it % 5).toByte() }
        uploadPart("big.bin", uid, 1, a, mapOf("x-amz-checksum-crc32" to Base64.getEncoder().encodeToString(crc32Raw(a))))
        uploadPart("big.bin", uid, 2, b, mapOf("x-amz-checksum-crc32" to Base64.getEncoder().encodeToString(crc32Raw(b))))
        val want = crc32Composite(listOf(crc32Raw(a), crc32Raw(b)))
        val crcA = Base64.getEncoder().encodeToString(crc32Raw(a))
        val crcB = Base64.getEncoder().encodeToString(crc32Raw(b))
        val e1 = uploadPart("big.bin", uid, 1, a, mapOf("x-amz-checksum-crc32" to crcA))
        val e2 = uploadPart("big.bin", uid, 2, b, mapOf("x-amz-checksum-crc32" to crcB))
        val wrong = s3(
            "POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1, 2 to e2)),
            extraHeaders = mapOf("x-amz-checksum-crc32" to "AAAAAAAAAAA-2"),
        )
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertTrue(wrong.bodyAsText().contains("<Code>BadDigest</Code>"), wrong.bodyAsText())
        assertEquals(0L, fileCount())
        val ok = s3(
            "POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1, 2 to e2)),
            extraHeaders = mapOf("x-amz-checksum-crc32" to want),
        )
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals(a.size + b.size, s3("GET", "/testbucket/big.bin").bodyAsBytes().size)
    }

    @Test
    fun aChecksumHeaderIsIgnoredWhenAPartLacksTheAlgorithm() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val a = ByteArray(5 * 1024 * 1024) { (it % 3).toByte() }
        val e1 = uploadPart("big.bin", uid, 1, a)
        val tail = "z".toByteArray()
        val e2 = uploadPart("big.bin", uid, 2, tail)
        val r = s3(
            "POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1, 2 to e2)),
            extraHeaders = mapOf("x-amz-checksum-crc32" to "AAAAAAAAAAA-2"),
        )
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(a.size + tail.size, s3("GET", "/testbucket/big.bin").bodyAsBytes().size)
    }

    @Test
    fun aPartBeyondTheServerCapIsEntityTooLarge() = testApplication {
        setup(maxFileSize = 1024)
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val r = s3("PUT", "/testbucket/big.bin", "partNumber=1&uploadId=$uid", body = ByteArray(2048) { 1 })
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>EntityTooLarge</Code>"), r.bodyAsText())
        assertEquals(0L, transaction(DatabaseFactory.db) { UploadChunksTable.selectAll().count() })
    }

    @Test
    fun createRejectsAKeyNamingAFolder() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/dir/a.txt", body = "x")
        val r = s3("POST", "/testbucket/dir", "uploads=")
        assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Code>InvalidRequest</Code>"), r.bodyAsText())
    }

    @Test
    fun createMakesImplicitParentsAndPublishesNothingYet() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        createMpu("x/y/big.bin")
        assertEquals(0L, fileCount())
        assertEquals(3L, transaction(DatabaseFactory.db) { FoldersTable.selectAll().count() })
    }

    @Test
    fun createOnAMissingBucketIs404() = testApplication {
        setup()
        val r = s3("POST", "/nosuchbucket/k.bin", "uploads=")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NoSuchBucket</Code>"), r.bodyAsText())
    }

    @Test
    fun completeWithAnUnknownUploadIdIsNoSuchUpload() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = s3(
            "POST", "/testbucket/big.bin", "uploadId=${UUID.randomUUID()}",
            body = completeBody(listOf(1 to "0".repeat(64))),
        )
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NoSuchUpload</Code>"), r.bodyAsText())
    }

    @Test
    fun completeRejectsAWrongRootElement() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val r = s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = "<Delete></Delete>")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>MalformedXML</Code>"), r.bodyAsText())
    }

    @Test
    fun completeRejectsAnEmptyPartList() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val r = s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = "<CompleteMultipartUpload></CompleteMultipartUpload>")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>MalformedXML</Code>"), r.bodyAsText())
    }

    @Test
    fun listPartsIsNotImplemented() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val r = s3("GET", "/testbucket/big.bin", "uploadId=$uid")
        assertEquals(HttpStatusCode.NotImplemented, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NotImplemented</Code>"), r.bodyAsText())
    }

    @Test
    fun readOnlyTokenCannotUseMultipart() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        switchToReadOnlyToken()
        assertEquals(HttpStatusCode.Forbidden, s3("POST", "/testbucket/a.bin", "uploads=").status)
    }

    @Test
    fun readOnlyTokenCannotUploadAPartOrAbort() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("a.bin")
        uploadPart("a.bin", uid, 1, "x".toByteArray())
        // 会话是用可写令牌建的；换成只读令牌之后，分片的写与中止同样要被挡。
        switchToReadOnlyToken()
        val part = s3("PUT", "/testbucket/a.bin", "partNumber=2&uploadId=$uid", body = "y")
        assertEquals(HttpStatusCode.Forbidden, part.status, part.bodyAsText())
        assertTrue(part.bodyAsText().contains("<Code>AccessDenied</Code>"), part.bodyAsText())
        val abort = s3("DELETE", "/testbucket/a.bin", "uploadId=$uid")
        assertEquals(HttpStatusCode.Forbidden, abort.status, abort.bodyAsText())
        assertTrue(abort.bodyAsText().contains("<Code>AccessDenied</Code>"), abort.bodyAsText())
        assertEquals(1L, transaction(DatabaseFactory.db) { UploadChunksTable.selectAll().count() })
    }

    @Test
    fun completeRejectsAWrongRootEvenWhenItCarriesParts() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("a.bin")
        val e1 = uploadPart("a.bin", uid, 1, "x".toByteArray())
        // 根元素错了但里面仍有 Part：只看"有没有 Part"会把它当成一次正常完成。
        val body = "<Foo><Part><PartNumber>1</PartNumber><ETag>&quot;$e1&quot;</ETag></Part></Foo>"
        val r = s3("POST", "/testbucket/a.bin", "uploadId=$uid", body = body)
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Code>MalformedXML</Code>"), r.bodyAsText())
        assertEquals(0L, fileCount())
    }

    @Test
    fun multipartOverwriteDoesNotCreateAVersion() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "one")
        val uid = createMpu("a.txt")
        val e1 = uploadPart("a.txt", uid, 1, ByteArray(5 * 1024 * 1024) { 4 })
        val e2 = uploadPart("a.txt", uid, 2, "tail".toByteArray())
        assertEquals(
            HttpStatusCode.OK,
            s3("POST", "/testbucket/a.txt", "uploadId=$uid", body = completeBody(listOf(1 to e1, 2 to e2))).status,
        )
        assertEquals(1L, fileCount())
        assertEquals(0L, transaction(DatabaseFactory.db) { FileVersionsTable.selectAll().count() })
    }

    @Test
    fun aCompletedMultipartIsVisibleToListingAndApi() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("x/y/big.bin")
        val e1 = uploadPart("x/y/big.bin", uid, 1, ByteArray(5 * 1024 * 1024) { 1 })
        val e2 = uploadPart("x/y/big.bin", uid, 2, "t".toByteArray())
        s3("POST", "/testbucket/x/y/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1, 2 to e2)))
        val list = s3("GET", "/testbucket", "list-type=2&delimiter=/")
        assertTrue(list.bodyAsText().contains("<Prefix>x/</Prefix>"), list.bodyAsText())
        val folder = transaction(DatabaseFactory.db) {
            FoldersTable.selectAll().where { FoldersTable.name eq "testbucket" }.single()[FoldersTable.id]
        }
        val api = client.get("/api/folders/$folder/contents") { header(HttpHeaders.Authorization, apiBearer) }
        assertEquals(HttpStatusCode.OK, api.status)
        assertTrue(api.bodyAsText().contains("x"), api.bodyAsText())
    }

    @Test
    fun anotherUsersUploadIdIsNoSuchUpload() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        // 换一个账号、换一个令牌：uploadId 不是能力凭证，别的用户拿到也不能续传。
        client.post("/api/auth/register") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"username":"user2","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"username":"user2","password":"password123"}""")
        }.bodyAsText()
        val bearer2 = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login)!!.groupValues[1]
        val created = client.post("/api/webdav/tokens") {
            header(HttpHeaders.Authorization, bearer2)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"label":"other","readOnly":false}""")
        }.bodyAsText()
        accessKey = Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1]
        secret = Regex(""""plaintext":"([^"]+)"""").find(created)!!.groupValues[1]
        val r = s3("PUT", "/testbucket/big.bin", "partNumber=1&uploadId=$uid", body = "x")
        assertEquals(HttpStatusCode.NotFound, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Code>NoSuchUpload</Code>"), r.bodyAsText())
    }

    @Test
    fun aTamperedStagingPartIsNotPublished() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val bytes = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        val etag = uploadPart("big.bin", uid, 1, bytes)
        // 账本里的 ETag 是上传那一刻算的；落盘之后字节被改过就不能发布出去。
        Files.write(partDir(uid).resolve("1.part"), ByteArray(5 * 1024 * 1024) { (it % 253).toByte() })
        val r = s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to etag)))
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertEquals(0L, fileCount())
    }

    @Test
    fun theSessionSurvivesAsCompletedAndIsCleanedByTheSweep() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val e1 = uploadPart("big.bin", uid, 1, "small".toByteArray())
        s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1)))
        val sid = UUID.fromString(uid)
        val status = transaction(DatabaseFactory.db) {
            UploadSessionsTable.selectAll().where { UploadSessionsTable.id eq sid }.single()[UploadSessionsTable.status]
        }
        assertEquals("completed", status)
        assertEquals(
            HttpStatusCode.NotFound,
            s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1))).status,
        )
    }

    @Test
    fun aPartBodyShorterThanItsDeclaredLengthIsRejected() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val (authz, amzDate) = authFor("PUT", "/testbucket/big.bin", "partNumber=1&uploadId=$uid")
        // 声明 1 KiB 只送 16 字节。截断的分片必须拒掉：放过去的话账本里会记下一个
        // 自洽却残缺的分片，最后拼出来的对象悄悄少一段，而 ETag 还算得对。
        val r = client.request("/testbucket/big.bin?partNumber=1&uploadId=$uid") {
            method = HttpMethod.Put
            header(HttpHeaders.Authorization, authz)
            header(HttpHeaders.Host, "localhost")
            header("x-amz-date", amzDate)
            header("x-amz-content-sha256", "UNSIGNED-PAYLOAD")
            setBody(object : OutgoingContent.ByteArrayContent() {
                override val contentLength: Long = 1024
                override fun bytes(): ByteArray = ByteArray(16) { 7 }
            })
        }
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Code>InvalidArgument</Code>"), r.bodyAsText())
        assertEquals(0L, transaction(DatabaseFactory.db) { UploadChunksTable.selectAll().count() })
        assertFalse(Files.exists(partDir(uid).resolve("1.part")))
    }

    @Test
    fun aCompletedTotalBeyondTheCurrentServerCapIsEntityTooLarge() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val uid = createMpu("big.bin")
        val e1 = uploadPart("big.bin", uid, 1, "hello".toByteArray())
        // 分片上传那一刻按当时的上限放行；上限是运行期可改的配置，完成时必须再核一次
        // 总量，否则上限调小之后已经传完的分片还能拼出超限对象。
        val before = UploadService.maxFileSize
        try {
            UploadService.maxFileSize = 3
            val r = s3("POST", "/testbucket/big.bin", "uploadId=$uid", body = completeBody(listOf(1 to e1)))
            assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
            assertTrue(r.bodyAsText().contains("<Code>EntityTooLarge</Code>"), r.bodyAsText())
            assertEquals(0L, fileCount())
        } finally {
            UploadService.maxFileSize = before
        }
    }
}
