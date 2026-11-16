package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.SigV4
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
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class S3BucketTest {
    private val storageDir = Files.createTempDirectory("bz-s3bucket").toString()
    private var accessKey = ""
    private var secret = ""
    private var userId = ""
    private var maxFileSize = 1L shl 30

    private suspend fun ApplicationTestBuilder.setup(maxFileSize: Long = 1L shl 30) {
        this@S3BucketTest.maxFileSize = maxFileSize
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

    /** 换一枚只读令牌来签后续请求；助手形状不变，只换字段。 */
    private suspend fun ApplicationTestBuilder.switchToReadOnlyToken() {
        val login = client.post("/api/auth/login") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"username":"user1","password":"password123"}""")
        }.bodyAsText()
        val bearer = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login)!!.groupValues[1]
        val created = client.post("/api/webdav/tokens") {
            header(HttpHeaders.Authorization, bearer)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"label":"ro","readOnly":true}""")
        }.bodyAsText()
        accessKey = Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1]
        secret = Regex(""""plaintext":"([^"]+)"""").find(created)!!.groupValues[1]
    }

    @Test
    fun createHeadAndDeleteABucket() = testApplication {
        setup()
        val put = s3("PUT", "/testbucket")
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        assertEquals("/testbucket", put.headers[HttpHeaders.Location])
        val head = s3("HEAD", "/testbucket")
        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals("us-east-1", head.headers["x-amz-bucket-region"])
        val del = s3("DELETE", "/testbucket")
        assertEquals(HttpStatusCode.NoContent, del.status)
        assertEquals(HttpStatusCode.NotFound, s3("HEAD", "/testbucket").status)
    }

    @Test
    fun creatingTwiceIsBucketAlreadyOwnedByYou() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = s3("PUT", "/testbucket")
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("<Code>BucketAlreadyOwnedByYou</Code>"), r.bodyAsText())
    }

    @Test
    fun anIllegalNameIsInvalidBucketName() = testApplication {
        setup()
        for (name in listOf("ab", "UPPER", "a..b", "192.168.0.1", "我的桶")) {
            val path = "/" + SigV4.uriEncode(name, encodeSlash = false)
            val r = s3("PUT", path)
            assertEquals(HttpStatusCode.BadRequest, r.status, "$name -> ${r.status} ${r.bodyAsText()}")
            assertTrue(r.bodyAsText().contains("<Code>InvalidBucketName</Code>"), r.bodyAsText())
        }
    }

    /**
     * spec §4.4 的两半：CreateBucket 用保留名 → 400 InvalidBucketName，
     * 其余访问 → 404 NoSuchBucket。PUT 带键是 PutObject 不是 CreateBucket，
     * 所以仍走 404 那一半。
     */
    @Test
    fun reservedNamesSplitByWhetherItIsACreate() = testApplication {
        setup()
        for (name in listOf("api", "dav", "health", "readyz")) {
            val create = s3("PUT", "/$name")
            assertEquals(HttpStatusCode.BadRequest, create.status, "$name -> ${create.status} ${create.bodyAsText()}")
            assertTrue(create.bodyAsText().contains("<Code>InvalidBucketName</Code>"), create.bodyAsText())
            val read = s3("GET", "/$name")
            assertEquals(HttpStatusCode.NotFound, read.status, "$name -> ${read.status}")
            assertTrue(read.bodyAsText().contains("<Code>NoSuchBucket</Code>"), read.bodyAsText())
            val obj = s3("PUT", "/$name/key.txt", body = "x")
            assertEquals(HttpStatusCode.NotFound, obj.status, "$name/key -> ${obj.status}")
            assertTrue(obj.bodyAsText().contains("<Code>NoSuchBucket</Code>"), obj.bodyAsText())
        }
    }

    @Test
    fun listBucketsShowsOnlyLegalTopLevelFolders() = testApplication {
        setup()
        s3("PUT", "/good-bucket")
        s3("PUT", "/another-bucket")
        transaction(DatabaseFactory.db) {
            FoldersTable.insert {
                it[FoldersTable.id] = UUID.randomUUID()
                it[FoldersTable.user] = UUID.fromString(userId)
                it[FoldersTable.parent] = null
                it[FoldersTable.name] = "中文文件夹"
            }
        }
        val r = s3("GET", "/")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val body = r.bodyAsText()
        assertTrue(body.contains("<Name>another-bucket</Name>"), body)
        assertTrue(body.contains("<Name>good-bucket</Name>"), body)
        assertTrue(body.indexOf("another-bucket") < body.indexOf("good-bucket"), "桶必须按名字升序: $body")
        assertFalse(body.contains("中文文件夹"), body)
        assertTrue(body.contains("ListAllMyBucketsResult"), body)
        assertTrue(body.contains("<DisplayName>user1</DisplayName>"), body)
        assertTrue(body.contains("<CreationDate>"), body)
    }

    @Test
    fun aNonEmptyBucketCannotBeDeleted() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        transaction(DatabaseFactory.db) {
            val bucket = FoldersTable.selectAll().where { FoldersTable.name eq "testbucket" }.single()[FoldersTable.id]
            FilesTable.insert {
                it[FilesTable.id] = UUID.randomUUID(); it[FilesTable.user] = UUID.fromString(userId)
                it[FilesTable.folder] = bucket; it[FilesTable.name] = "x.txt"; it[FilesTable.size] = 1
                it[FilesTable.sha256] = "0".repeat(64); it[FilesTable.storageKey] = "k"
                it[FilesTable.deletedAt] = System.currentTimeMillis()
            }
        }
        val r = s3("DELETE", "/testbucket")
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("<Code>BucketNotEmpty</Code>"), r.bodyAsText())
    }

    /** 空子目录也算非空：否则 DeleteBucket 会连带删掉 App 侧建的目录树。 */
    @Test
    fun aBucketWithAnEmptySubfolderCannotBeDeleted() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        transaction(DatabaseFactory.db) {
            FoldersTable.insert {
                it[FoldersTable.id] = UUID.randomUUID(); it[FoldersTable.user] = UUID.fromString(userId)
                it[FoldersTable.parent] = bucketId("testbucket"); it[FoldersTable.name] = "sub"
            }
        }
        val r = s3("DELETE", "/testbucket")
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("<Code>BucketNotEmpty</Code>"), r.bodyAsText())
    }

    @Test
    fun aBucketWithAnOpenUploadSessionCannotBeDeleted() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        transaction(DatabaseFactory.db) {
            UploadSessionsTable.insert {
                it[UploadSessionsTable.id] = UUID.randomUUID(); it[UploadSessionsTable.user] = UUID.fromString(userId)
                it[UploadSessionsTable.folder] = bucketId("testbucket"); it[UploadSessionsTable.name] = "pending.bin"
                it[UploadSessionsTable.size] = 1; it[UploadSessionsTable.chunkSize] = 1
                it[UploadSessionsTable.expiresAt] = System.currentTimeMillis() + 60_000
            }
        }
        val r = s3("DELETE", "/testbucket")
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("<Code>BucketNotEmpty</Code>"), r.bodyAsText())
    }

    @Test
    fun aCompletedUploadSessionDoesNotBlockBucketDeletion() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        // 已完成的会话行还会按 FK 指着桶 24h：算进"非空"会让刚 PUT 过的桶删不掉。
        transaction(DatabaseFactory.db) {
            UploadSessionsTable.insert {
                it[UploadSessionsTable.id] = UUID.randomUUID(); it[UploadSessionsTable.user] = UUID.fromString(userId)
                it[UploadSessionsTable.folder] = bucketId("testbucket"); it[UploadSessionsTable.name] = "done.bin"
                it[UploadSessionsTable.size] = 1; it[UploadSessionsTable.chunkSize] = 1
                it[UploadSessionsTable.status] = "completed"
                it[UploadSessionsTable.expiresAt] = System.currentTimeMillis() + 24L * 3600 * 1000
            }
        }
        assertEquals(HttpStatusCode.NoContent, s3("DELETE", "/testbucket").status)
        assertEquals(HttpStatusCode.NotFound, s3("HEAD", "/testbucket").status)
    }

    @Test
    fun aLiveFileAtTheRootBlocksTheBucketName() = testApplication {
        setup()
        transaction(DatabaseFactory.db) {
            FilesTable.insert {
                it[FilesTable.id] = UUID.randomUUID(); it[FilesTable.user] = UUID.fromString(userId)
                it[FilesTable.folder] = null; it[FilesTable.name] = "testbucket"; it[FilesTable.size] = 1
                it[FilesTable.sha256] = "0".repeat(64); it[FilesTable.storageKey] = "k"
            }
        }
        val r = s3("PUT", "/testbucket")
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("<Code>BucketAlreadyExists</Code>"), r.bodyAsText())
    }

    @Test
    fun locationAndVersioningAreAnswered() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val loc = s3("GET", "/testbucket", "location=")
        assertEquals(HttpStatusCode.OK, loc.status)
        assertTrue(loc.bodyAsText().contains("LocationConstraint"), loc.bodyAsText())
        val ver = s3("GET", "/testbucket", "versioning=")
        assertTrue(ver.bodyAsText().contains("VersioningConfiguration"), ver.bodyAsText())
        assertEquals(HttpStatusCode.NotImplemented, s3("PUT", "/testbucket", "versioning=").status)
        val ups = s3("GET", "/testbucket", "uploads=")
        assertEquals(HttpStatusCode.OK, ups.status)
        assertTrue(ups.bodyAsText().contains("ListMultipartUploadsResult"), ups.bodyAsText())
        assertTrue(ups.bodyAsText().contains("<IsTruncated>false</IsTruncated>"), ups.bodyAsText())
    }

    @Test
    fun unsupportedSubresourcesAre501() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        for (sub in listOf("acl", "policy", "tagging", "cors", "lifecycle", "versionId")) {
            val r = s3("GET", "/testbucket", "$sub=")
            assertEquals(HttpStatusCode.NotImplemented, r.status, "$sub -> ${r.status}")
            assertTrue(r.bodyAsText().contains("<Code>NotImplemented</Code>"), r.bodyAsText())
        }
    }

    @Test
    fun aBadListTypeValueIsInvalidArgument() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        // 不写 list-type 走 V1（rclone 的 auto 会这么发），写了就必须是 2。
        val r = s3("GET", "/testbucket", "list-type=3")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidArgument</Code>"), r.bodyAsText())
    }

    @Test
    fun aMissingBucketIsNoSuchBucketOnEveryRead() = testApplication {
        setup()
        // 裸 GET（V1）也一样按桶存不存在判定，与分页参数无关。
        for (q in listOf("", "list-type=2", "location=", "versioning=", "uploads=")) {
            val r = s3("GET", "/absentbucket", q)
            assertEquals(HttpStatusCode.NotFound, r.status, "$q -> ${r.status} ${r.bodyAsText()}")
            assertTrue(r.bodyAsText().contains("<Code>NoSuchBucket</Code>"), r.bodyAsText())
        }
        assertEquals(HttpStatusCode.NotFound, s3("HEAD", "/absentbucket").status)
        assertEquals(HttpStatusCode.NotFound, s3("DELETE", "/absentbucket").status)
    }

    @Test
    fun aReadOnlyTokenCannotCreateOrDelete() = testApplication {
        setup()
        switchToReadOnlyToken()
        val put = s3("PUT", "/testbucket")
        assertEquals(HttpStatusCode.Forbidden, put.status)
        assertTrue(put.bodyAsText().contains("<Code>AccessDenied</Code>"), put.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, s3("DELETE", "/testbucket").status)
        // 读仍然放行：桶不存在是 404 而不是 403，证明只读限制只落在写方法上
        assertEquals(HttpStatusCode.NotFound, s3("HEAD", "/testbucket").status)
    }

    @Test
    fun everyResponseCarriesARequestId() = testApplication {
        setup()
        val ok = s3("PUT", "/testbucket")
        assertTrue(ok.headers.contains("x-amz-request-id"), "成功响应也要带 request-id")
        val err = s3("GET", "/absentbucket")
        assertTrue(err.headers.contains("x-amz-request-id"), "错误响应也要带 request-id")
        assertTrue(err.bodyAsText().contains("<RequestId>"), err.bodyAsText())
    }
}