package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.BlobDeleteQueueTable
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.files.BLOB_GRACE_MS
import com.linan.barezen_drive.files.processQueue
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.SigV4
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class S3DeleteObjectsTest {
    private val storageDir = Files.createTempDirectory("bz-s3del").toString()
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

    private fun md5B64(b: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(b))

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun deleteBody(keys: List<String>, quiet: Boolean = false) = buildString {
        append("<Delete>")
        if (quiet) append("<Quiet>true</Quiet>")
        keys.forEach { append("<Object><Key>").append(xmlEscape(it)).append("</Key></Object>") }
        append("</Delete>")
    }

    private suspend fun ApplicationTestBuilder.deleteObjects(body: String, md5: Boolean = true): HttpResponse {
        val headers = HashMap<String, String>()
        if (md5) headers["Content-MD5"] = md5B64(body.toByteArray())
        headers["Content-Type"] = "application/xml"
        return s3("POST", "/testbucket", "delete=", body = body, extraHeaders = headers)
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
    fun deletesAndIsIdempotent() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        val r = deleteObjects(deleteBody(listOf("a.txt", "missing.txt")))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Deleted><Key>a.txt</Key></Deleted>"), r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Deleted><Key>missing.txt</Key></Deleted>"), r.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, s3("GET", "/testbucket/a.txt").status)
        assertEquals(0L, transaction(DatabaseFactory.db) { FilesTable.selectAll().count() })
        val again = deleteObjects(deleteBody(listOf("a.txt")))
        assertEquals(HttpStatusCode.OK, again.status, again.bodyAsText())
    }

    @Test
    fun quietModeReturnsOnlyErrors() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        val r = deleteObjects(deleteBody(listOf("a.txt"), quiet = true))
        assertEquals(HttpStatusCode.OK, r.status)
        assertFalse(r.bodyAsText().contains("<Deleted>"), r.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, s3("GET", "/testbucket/a.txt").status)
    }

    @Test
    fun missingContentMd5Is400() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        val r = deleteObjects(deleteBody(listOf("a.txt")), md5 = false)
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidRequest</Code>"), r.bodyAsText())
        assertEquals("x", s3("GET", "/testbucket/a.txt").bodyAsText())
    }

    @Test
    fun wrongContentMd5IsInvalidDigest() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        val r = s3("POST", "/testbucket", "delete=", body = deleteBody(listOf("a.txt")), extraHeaders = mapOf("Content-MD5" to "AAAA"))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidDigest</Code>"), r.bodyAsText())
        assertEquals("x", s3("GET", "/testbucket/a.txt").bodyAsText())
    }

    @Test
    fun overAThousandObjectsIsMalformedXML() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = deleteObjects(deleteBody((1..1001).map { "k$it.txt" }))
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>MalformedXML</Code>"), r.bodyAsText())
    }

    @Test
    fun exactlyAThousandObjectsIsAccepted() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = deleteObjects(deleteBody((1..1000).map { "k$it.txt" }))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(1000, Regex("<Deleted>").findAll(r.bodyAsText()).count())
    }

    @Test
    fun doctypeIsRefused() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        val body = """<!DOCTYPE Delete [<!ENTITY x SYSTEM "file:///etc/passwd">]><Delete><Object><Key>&x;</Key></Object></Delete>"""
        val r = deleteObjects(body)
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>MalformedXML</Code>"), r.bodyAsText())
        assertEquals("x", s3("GET", "/testbucket/a.txt").bodyAsText())
    }

    @Test
    fun anEmptyBodyIsMalformedXML() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = deleteObjects("")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>MalformedXML</Code>"), r.bodyAsText())
    }

    @Test
    fun aWrongRootElementIsMalformedXML() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = deleteObjects("<CompleteMultipartUpload></CompleteMultipartUpload>")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>MalformedXML</Code>"), r.bodyAsText())
    }

    @Test
    fun anObjectWithoutAKeyIsMalformedXML() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = deleteObjects("<Delete><Object></Object></Delete>")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>MalformedXML</Code>"), r.bodyAsText())
    }

    @Test
    fun anOversizedBodyIs400() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val body = "<Delete><Object><Key>" + "a".repeat(1100 * 1024) + "</Key></Object></Delete>"
        val r = deleteObjects(body)
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidRequest</Code>"), r.bodyAsText())
    }

    @Test
    fun keysAreXmlEscapedInTheResponse() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = deleteObjects(deleteBody(listOf("a<b>&c.txt")))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Key>a&lt;b&gt;&amp;c.txt</Key>"), r.bodyAsText())
        assertFalse(r.bodyAsText().contains("a<b>"), r.bodyAsText())
    }

    @Test
    fun deletesNestedKeysAndPrunesAncestors() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a/b/c.txt", body = "x")
        s3("PUT", "/testbucket/a/d.txt", body = "y")
        val r = deleteObjects(deleteBody(listOf("a/b/c.txt", "a/d.txt")))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(1L, transaction(DatabaseFactory.db) { FoldersTable.selectAll().count() })
        assertEquals(HttpStatusCode.OK, s3("HEAD", "/testbucket").status)
        assertEquals(HttpStatusCode.NoContent, s3("DELETE", "/testbucket").status)
    }

    @Test
    fun aFolderKeyIsReportedDeletedButKeepsItsChildren() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/dir/a.txt", body = "x")
        val r = deleteObjects(deleteBody(listOf("dir")))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Deleted><Key>dir</Key></Deleted>"), r.bodyAsText())
        assertEquals("x", s3("GET", "/testbucket/dir/a.txt").bodyAsText())
    }

    @Test
    fun anIllegalKeyIsReportedDeleted() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        val r = deleteObjects(deleteBody(listOf("a//b.txt")))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertTrue(r.bodyAsText().contains("<Deleted><Key>a//b.txt</Key></Deleted>"), r.bodyAsText())
    }

    @Test
    fun theBlobIsQueuedNotUnlinked() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "queue me")
        val sha = sha256Hex("queue me")
        val blob = Path.of(storageDir, "blobs", sha.substring(0, 2), sha.substring(2, 4), sha)
        assertEquals(HttpStatusCode.OK, deleteObjects(deleteBody(listOf("a.txt"))).status)
        assertTrue(Files.exists(blob), "宽限期内 blob 必须还在")
        assertEquals(1L, transaction(DatabaseFactory.db) { BlobDeleteQueueTable.selectAll().count() })
        processQueue(LocalStorageProvider(Path.of(storageDir)), now = System.currentTimeMillis() + BLOB_GRACE_MS + 1)
        assertTrue(!Files.exists(blob), "清扫之后该被回收")
    }

    @Test
    fun aSharedBlobSurvivesABatchDelete() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "same")
        s3("PUT", "/testbucket/b.txt", body = "same")
        val sha = sha256Hex("same")
        val blob = Path.of(storageDir, "blobs", sha.substring(0, 2), sha.substring(2, 4), sha)
        deleteObjects(deleteBody(listOf("a.txt")))
        processQueue(LocalStorageProvider(Path.of(storageDir)), now = System.currentTimeMillis() + BLOB_GRACE_MS + 1)
        assertTrue(Files.exists(blob), "另一个名字还引用着它")
        assertEquals("same", s3("GET", "/testbucket/b.txt").bodyAsText())
    }

    @Test
    fun aMissingBucketIs404() = testApplication {
        setup()
        val body = deleteBody(listOf("a.txt"))
        val r = s3("POST", "/nosuchbucket", "delete=", body = body, extraHeaders = mapOf("Content-MD5" to md5B64(body.toByteArray())))
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.bodyAsText().contains("<Code>NoSuchBucket</Code>"), r.bodyAsText())
    }

    @Test
    fun readOnlyTokenCannotDeleteObjects() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        switchToReadOnlyToken()
        val r = deleteObjects(deleteBody(listOf("a.txt")))
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("<Code>AccessDenied</Code>"), r.bodyAsText())
        assertEquals("x", s3("GET", "/testbucket/a.txt").bodyAsText())
    }

    @Test
    fun aBatchDeleteIsVisibleToTheApi() = testApplication {
        setup()
        s3("PUT", "/testbucket")
        s3("PUT", "/testbucket/a.txt", body = "x")
        deleteObjects(deleteBody(listOf("a.txt")))
        val trash = client.request("/api/trash") { header(HttpHeaders.Authorization, apiBearer) }
        assertEquals(HttpStatusCode.OK, trash.status)
        assertFalse(trash.bodyAsText().contains("a.txt"), trash.bodyAsText())
    }
}