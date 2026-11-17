package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.SigV4
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 根路径与 SPA 共用命名空间，所以"谁接管这条 URL"必须逐条钉住，不能靠
 * "Ktor 应该会让常量路由优先"。四条优先级 + 保留桶名 + 未加签请求不被抢。
 */
class S3RoutingTest {
    private val storageDir = Files.createTempDirectory("bz-s3route").toString()
    private var accessKey = ""
    private var secret = ""

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
        val bearer = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login)!!.groupValues[1]
        val created = client.post("/api/webdav/tokens") {
            header(HttpHeaders.Authorization, bearer)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"label":"s3","readOnly":false}""")
        }.bodyAsText()
        accessKey = Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1]
        secret = Regex(""""plaintext":"([^"]+)"""").find(created)!!.groupValues[1]
    }

    /** 给任意 method/path 签名；query 原样参与规范化。 */
    private fun authFor(
        method: String, path: String, query: String = "", payloadHash: String = S3_UNSIGNED,
        date: String = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now()),
    ): String {
        val headers = linkedMapOf("host" to "localhost", "x-amz-date" to date, "x-amz-content-sha256" to payloadHash)
        return SigV4.authorization(
            method, SigV4.canonicalUriRaw(path), SigV4.canonicalQueryRaw(query),
            headers, payloadHash, accessKey, secret, "us-east-1", "s3", date, date.substring(0, 8),
        )
    }

    // 签名里的 x-amz-date 与真正发出的头必须是同一个值：各算一次 Instant.now()
    // 会在跨秒时签出一个头里没有的时间戳，签名必然不符。
    private suspend fun ApplicationTestBuilder.signedGet(path: String, query: String = ""): HttpResponse {
        val date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
        return client.get(if (query.isEmpty()) path else "$path?$query") {
            header(HttpHeaders.Authorization, authFor("GET", path, query, date = date))
            // 测试宿主不会替我们填 Host，而 host 是 SignedHeaders 里的一员：
            // 不显式发出去，服务端规范化出来的就是 "host:"，签名必然不符。
            header(HttpHeaders.Host, "localhost")
            header("x-amz-content-sha256", S3_UNSIGNED)
            header("x-amz-date", date)
        }
    }

    @Test
    fun healthStillWins() = testApplication {
        setup()
        val r = client.get("/health")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"status\":\"ok\""), r.bodyAsText())
    }

    @Test
    fun unsignedAssetStillComesFromTheClasspath() = testApplication {
        setup()
        val fixture = requireNotNull(javaClass.classLoader.getResource("web/chart..final.png")).readBytes()
        val r = client.get("/chart..final.png")
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(fixture.toList(), r.bodyAsBytes().toList())
        assertEquals("public, max-age=31536000, immutable", r.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun unsignedSpaDeepLinkStillGetsIndexHtml() = testApplication {
        setup()
        // 测试 classpath 里没有 web/index.html，所以 SPA 回退应给 404；关键是
        // 它绝不能被 S3 接管成 403 XML。
        val r = client.get("/files/xyz")
        assertFalse(r.bodyAsText().contains("<Error>"), "SPA 深链被 S3 抢走了: ${r.bodyAsText()}")
        assertFalse(r.bodyAsText().contains("AccessDenied"), r.bodyAsText())
    }

    @Test
    fun unsignedRootIsNotListBuckets() = testApplication {
        setup()
        val r = client.get("/")
        assertFalse(r.bodyAsText().contains("ListAllMyBucketsResult"), r.bodyAsText())
        assertFalse(r.bodyAsText().contains("<Error>"), r.bodyAsText())
    }

    @Test
    fun aSignedRequestEntersS3() = testApplication {
        setup()
        val r = signedGet("/somebucket")
        // 桶还不存在；骨架阶段任何 S3 形状的回答都算"进入了 S3"，但必须是 XML。
        assertTrue(r.headers.contains("x-amz-request-id"), "没有 x-amz-request-id，说明没进 S3")
        val body = r.bodyAsText()
        assertTrue(body.startsWith("<?xml") || r.status == HttpStatusCode.NotModified, body)
        // 只断言"是 XML"会被 403 错误体满足，等于没测鉴权：必须排除鉴权失败的两种码。
        assertFalse(body.contains("<Code>SignatureDoesNotMatch</Code>"), "签名没通过: $body")
        assertFalse(body.contains("<Code>AccessDenied</Code>"), "鉴权没通过: $body")
    }

    @Test
    fun reservedBucketNamesAreNeverTheSpa() = testApplication {
        setup()
        for (name in listOf("api", "dav", "health", "readyz")) {
            val r = signedGet("/$name")
            assertEquals(HttpStatusCode.NotFound, r.status, "$name -> ${r.status} ${r.bodyAsText()}")
            assertTrue(r.bodyAsText().contains("<Code>NoSuchBucket</Code>"), r.bodyAsText())
        }
    }

    @Test
    fun anUnsignedPutIsTreatedAsS3() = testApplication {
        setup()
        val r = client.put("/somebucket/key") { setBody("x") }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("<Code>AccessDenied</Code>"), r.bodyAsText())
    }

    @Test
    fun anAmzQueryParameterMarksS3EvenOnGet() = testApplication {
        setup()
        val r = client.get("/somebucket?X-Amz-Algorithm=AWS4-HMAC-SHA256")
        assertTrue(r.headers.contains("x-amz-request-id"), r.bodyAsText())
    }

    @Test
    fun unmatchedApiPathStillAnswers404NotS3() = testApplication {
        setup()
        val r = client.get("/api/definitely-not-a-route")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertFalse(r.bodyAsText().contains("<Error>"), r.bodyAsText())
    }

    private companion object { const val S3_UNSIGNED = "UNSIGNED-PAYLOAD" }
}