package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.BlobDeleteQueueTable
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FileVersionsTable
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.files.BLOB_GRACE_MS
import com.linan.barezen_drive.files.FileService
import com.linan.barezen_drive.files.processQueue
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.SigV4
import com.linan.barezen_drive.webdav.DavProperties
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S3CopyTest {
    private val storageDir = Files.createTempDirectory("bz-s3copy").toString()
    private var accessKey = ""
    private var secret = ""
    private var apiBearer = ""

    private suspend fun ApplicationTestBuilder.setup() {
        val cfg = AppConfig(
            0, "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30, registrationOpen = true,
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

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun blobPath(sha: String): Path =
        Path.of(storageDir, "blobs", sha.substring(0, 2), sha.substring(2, 4), sha)

    private suspend fun ApplicationTestBuilder.switchToReadOnlyToken() {
        val created = client.post("/api/webdav/tokens") {
            header(HttpHeaders.Authorization, apiBearer)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"label":"ro","readOnly":true}""")
        }.bodyAsText()
        accessKey = Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1]
        secret = Regex(""""plaintext":"([^"]+)"""").find(created)!!.groupValues[1]
    }

    private fun copyHeaders(source: String, vararg extra: Pair<String, String>): Map<String, String> =
        mapOf("x-amz-copy-source" to source) + extra.toMap()

    @Test
    fun copyWithinAndAcrossBuckets() = testApplication {
        setup()
        s3("PUT", "/src-bucket"); s3("PUT", "/dst-bucket")
        s3("PUT", "/src-bucket/a.txt", body = "copy me", extraHeaders = mapOf("x-amz-meta-mtime" to "1.0"))
        val r = s3("PUT", "/dst-bucket/b.txt", extraHeaders = copyHeaders("/src-bucket/a.txt"))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("CopyObjectResult"), r.bodyAsText())
        assertTrue(r.bodyAsText().contains("&quot;${sha256Hex("copy me")}&quot;"), r.bodyAsText())
        assertEquals("copy me", s3("GET", "/dst-bucket/b.txt").bodyAsText())
        assertEquals("1.0", s3("GET", "/dst-bucket/b.txt").headers["x-amz-meta-mtime"])
        assertEquals("copy me", s3("GET", "/src-bucket/a.txt").bodyAsText())
    }

    @Test
    fun copyCreatesImplicitParents() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        assertEquals(HttpStatusCode.OK, s3("PUT", "/testbucket/p/q/b.txt", extraHeaders = copyHeaders("/testbucket/a.txt")).status)
        assertEquals("x", s3("GET", "/testbucket/p/q/b.txt").bodyAsText())
    }

    @Test
    fun copyOverwritesWithoutAVersion() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "new")
        s3("PUT", "/testbucket/b.txt", body = "old")
        assertEquals(HttpStatusCode.OK, s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders("/testbucket/a.txt")).status)
        assertEquals("new", s3("GET", "/testbucket/b.txt").bodyAsText())
        assertEquals(0L, transaction(DatabaseFactory.db) { FileVersionsTable.selectAll().count() })
    }

    @Test
    fun overwriteCopyQueuesOnlyTheOldTargetBlob() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "new")
        s3("PUT", "/testbucket/b.txt", body = "old")
        val oldSha = sha256Hex("old")
        val newSha = sha256Hex("new")
        s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders("/testbucket/a.txt"))
        // 旧目标的字节进了宽限期队列，源共享的字节一个都没动
        assertEquals(1L, transaction(DatabaseFactory.db) { BlobDeleteQueueTable.selectAll().count() })
        assertTrue(Files.exists(blobPath(newSha)))
        assertTrue(Files.exists(blobPath(oldSha)))
        processQueue(LocalStorageProvider(Path.of(storageDir)), now = System.currentTimeMillis() + BLOB_GRACE_MS + 1)
        assertTrue(!Files.exists(blobPath(oldSha)), "旧目标的 blob 该被回收")
        assertTrue(Files.exists(blobPath(newSha)), "源的 blob 不能被回收")
        assertEquals("new", s3("GET", "/testbucket/a.txt").bodyAsText())
    }

    @Test
    fun replaceDirectiveOverridesMetadata() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x", extraHeaders = mapOf("x-amz-meta-mtime" to "1.0"))
        s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders(
            "/testbucket/a.txt", "x-amz-metadata-directive" to "REPLACE", "x-amz-meta-other" to "2.0",
        ))
        val got = s3("GET", "/testbucket/b.txt")
        assertNull(got.headers["x-amz-meta-mtime"])
        assertEquals("2.0", got.headers["x-amz-meta-other"])
    }

    @Test
    fun selfCopyWithCopyDirectiveIs400() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        val r = s3("PUT", "/testbucket/a.txt", extraHeaders = copyHeaders("/testbucket/a.txt"))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidRequest</Code>"), r.bodyAsText())
        assertEquals("x", s3("GET", "/testbucket/a.txt").bodyAsText())
    }

    @Test
    fun selfCopyWithReplaceKeepsTheBytes() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "keep me", extraHeaders = mapOf("x-amz-meta-mtime" to "1.0"))
        val sha = sha256Hex("keep me")
        val r = s3("PUT", "/testbucket/a.txt", extraHeaders = copyHeaders(
            "/testbucket/a.txt", "x-amz-metadata-directive" to "REPLACE", "x-amz-meta-mtime" to "2.0",
        ))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        // 源就是目标：删旧插新会把唯一的引用删掉，字节被排队回收、源行也读不到了
        assertEquals("keep me", s3("GET", "/testbucket/a.txt").bodyAsText())
        assertEquals("2.0", s3("GET", "/testbucket/a.txt").headers["x-amz-meta-mtime"])
        assertTrue(Files.exists(blobPath(sha)))
        assertEquals(0L, transaction(DatabaseFactory.db) { BlobDeleteQueueTable.selectAll().count() })
        assertEquals(1L, transaction(DatabaseFactory.db) { FilesTable.selectAll().count() })
    }

    @Test
    fun aSourceThatIsAPrefixIs404() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/dir/a.txt", body = "x")
        val r = s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders("/testbucket/dir"))
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NoSuchKey</Code>"), r.bodyAsText())
    }

    @Test
    fun aMissingSourceBucketIs404() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders("/nosuchbucket/a.txt"))
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NoSuchBucket</Code>"), r.bodyAsText())
    }

    @Test
    fun aMissingSourceKeyIs404() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders("/testbucket/nope.txt"))
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NoSuchKey</Code>"), r.bodyAsText())
    }

    @Test
    fun copySourceIfMatchIs412() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        val r = s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders("/testbucket/a.txt", "x-amz-copy-source-if-match" to "\"wrong\""))
        assertEquals(HttpStatusCode.PreconditionFailed, r.status)
        assertTrue(r.bodyAsText().contains("<Code>PreconditionFailed</Code>"), r.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, s3("GET", "/testbucket/b.txt").status)
    }

    @Test
    fun copySourceIfMatchAcceptsTheRealEtag() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        val r = s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders(
            "/testbucket/a.txt", "x-amz-copy-source-if-match" to "\"${sha256Hex("x")}\"",
        ))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    }

    @Test
    fun copySourceIfNoneMatchIs412WhenItMatches() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        val r = s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders(
            "/testbucket/a.txt", "x-amz-copy-source-if-none-match" to "\"${sha256Hex("x")}\"",
        ))
        assertEquals(HttpStatusCode.PreconditionFailed, r.status)
        val ok = s3("PUT", "/testbucket/c.txt", extraHeaders = copyHeaders("/testbucket/a.txt", "x-amz-copy-source-if-none-match" to "\"wrong\""))
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
    }

    @Test
    fun copySourceSinceHeadersAre412() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        val past = "Wed, 01 Jan 2020 00:00:00 GMT"
        val future = "Wed, 01 Jan 2090 00:00:00 GMT"
        // 源在 past 之后改过 → if-modified-since: future 不成立
        assertEquals(HttpStatusCode.PreconditionFailed, s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders("/testbucket/a.txt", "x-amz-copy-source-if-modified-since" to future)).status)
        assertEquals(HttpStatusCode.OK, s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders("/testbucket/a.txt", "x-amz-copy-source-if-modified-since" to past)).status)
        // 源在 past 之后改过 → if-unmodified-since: past 不成立
        assertEquals(HttpStatusCode.PreconditionFailed, s3("PUT", "/testbucket/c.txt", extraHeaders = copyHeaders("/testbucket/a.txt", "x-amz-copy-source-if-unmodified-since" to past)).status)
        assertEquals(HttpStatusCode.OK, s3("PUT", "/testbucket/c.txt", extraHeaders = copyHeaders("/testbucket/a.txt", "x-amz-copy-source-if-unmodified-since" to future)).status)
    }

    @Test
    fun anUnmodifiedSinceWithinTheSameSecondAcceptsTheCopy() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        val updatedAt = transaction(DatabaseFactory.db) {
            FilesTable.selectAll().where { FilesTable.name eq "a.txt" }.single()[FilesTable.updatedAt]
        }
        // DavProperties.httpDate 把毫秒截成整秒：如果比较不先把 updatedAt 也截到整秒，
        // 同一秒内的复制会被判成"改过"而 412。
        val sameSecond = DavProperties.httpDate(updatedAt)
        assertEquals(HttpStatusCode.OK, s3("PUT", "/testbucket/b.txt",
            extraHeaders = copyHeaders("/testbucket/a.txt", "x-amz-copy-source-if-unmodified-since" to sameSecond)).status)
    }

    @Test
    fun theRowCopyRejectsAnIllegalName() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        val triple = transaction(DatabaseFactory.db) {
            val b = FoldersTable.selectAll().where { FoldersTable.name eq "testbucket" }.single()
            val f = FilesTable.selectAll().where { FilesTable.name eq "a.txt" }.single()
            Triple(b[FoldersTable.user], f[FilesTable.id], b[FoldersTable.id])
        }
        // S3 的键解析早就挡了这些名字；这一条守的是搬进 copyFileRow 的那次校验还在。
        assertFailsWith<ApiException> { FileService.copyFile(triple.first, triple.second, triple.third, "bad/name") }
        assertFailsWith<ApiException> { FileService.copyFile(triple.first, triple.second, triple.third, "") }
        assertFailsWith<ApiException> { FileService.copyFile(triple.first, triple.second, triple.third, "..") }
    }

    @Test
    fun aTargetThatIsAFolderIs409() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        s3("PUT", "/testbucket/dir/b.txt", body = "y")
        val r = s3("PUT", "/testbucket/dir", extraHeaders = copyHeaders("/testbucket/a.txt"))
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidRequest</Code>"), r.bodyAsText())
    }

    @Test
    fun aBadDirectiveIs400() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        val r = s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders("/testbucket/a.txt", "x-amz-metadata-directive" to "MERGE"))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidArgument</Code>"), r.bodyAsText())
    }

    @Test
    fun aMalformedCopySourceIs400() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders("just-a-bucket"))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidArgument</Code>"), r.bodyAsText())
    }

    @Test
    fun aCopySourceWithVersionIdIs501() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        val r = s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders("/testbucket/a.txt?versionId=abc"))
        assertEquals(HttpStatusCode.NotImplemented, r.status)
    }

    @Test
    fun aPercentEncodedCopySourceResolves() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a%20b.txt", body = "spaces")
        // 桶名那一段也是编码过的：%62 == 'b'
        val r = s3("PUT", "/testbucket/c.txt", extraHeaders = copyHeaders("/test%62ucket/a%20b.txt"))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("spaces", s3("GET", "/testbucket/c.txt").bodyAsText())
    }

    @Test
    fun readOnlyTokenCannotCopy() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        switchToReadOnlyToken()
        val r = s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders("/testbucket/a.txt"))
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("<Code>AccessDenied</Code>"), r.bodyAsText())
    }

    @Test
    fun aCopyIsVisibleToTheApi() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        s3("PUT", "/testbucket/b.txt", extraHeaders = copyHeaders("/testbucket/a.txt"))
        val bucket = transaction(DatabaseFactory.db) {
            FoldersTable.selectAll().where { FoldersTable.name eq "testbucket" }.single()[FoldersTable.id]
        }
        val listing = client.get("/api/folders/$bucket/contents") { header(HttpHeaders.Authorization, apiBearer) }
        assertEquals(HttpStatusCode.OK, listing.status)
        assertTrue(listing.bodyAsText().contains("b.txt"), listing.bodyAsText())
        assertEquals(2L, transaction(DatabaseFactory.db) { FilesTable.selectAll().count() })
    }
}