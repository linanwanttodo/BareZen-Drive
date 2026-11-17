package com.linan.barezen_drive

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.s3.S3Exception
import com.linan.barezen_drive.s3.childText
import com.linan.barezen_drive.s3.readS3Xml
import com.linan.barezen_drive.s3.respondS3Error
import com.linan.barezen_drive.s3.toS3Exception
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S3 的错误体是 XML，不能走 StatusPages 的 JSON 信封；HEAD 的错误没有 body。
 * 用一个测试专用路由触发渲染，不需要等 S3 路由存在。
 */
class S3ErrorsTest {
    private val probeError = S3Exception("NoSuchKey", HttpStatusCode.NotFound, "键 <a> & \"b\" 不存在")

    private fun ApplicationTestBuilder.setup() {
        val dir = Files.createTempDirectory("bz-s3err").toString()
        val cfg = AppConfig(
            0, "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", dir, 1L shl 30, registrationOpen = true,
        )
        application {
            module(cfg, LocalStorageProvider(Path.of(cfg.storageDir)))
            routing {
                get("/probe-error") { call.respondS3Error(probeError, "rid-1") }
                // Ktor 不会替 get 路由自动应答 HEAD，不显式注册就是 405。
                head("/probe-error") { call.respondS3Error(probeError, "rid-1") }
                post("/probe-xml") {
                    val rid = UUID.randomUUID().toString()
                    try {
                        val root = call.readS3Xml()
                        call.respond(HttpStatusCode.OK, root.childText("Key") ?: "")
                    } catch (e: S3Exception) {
                        call.respondS3Error(e, rid)
                    }
                }
            }
        }
    }

    @Test
    fun errorBodyIsXmlWithTheEscapedMessage() = testApplication {
        setup()
        val r = client.get("/probe-error")
        assertEquals(HttpStatusCode.NotFound, r.status)
        val body = r.bodyAsText()
        assertTrue(body.startsWith("<?xml"), body)
        assertTrue(body.contains("<Code>NoSuchKey</Code>"), body)
        assertTrue(body.contains("&lt;a&gt; &amp; &quot;b&quot;"), body)
        assertTrue(body.contains("<RequestId>rid-1</RequestId>"), body)
        assertFalse(body.contains("\"error\""), "不能是 JSON 信封: $body")
        assertEquals("application/xml", r.contentType()?.withoutParameters()?.toString())
    }

    @Test
    fun headErrorsHaveNoBody() = testApplication {
        setup()
        val r = client.head("/probe-error")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertEquals("", r.bodyAsText())
    }

    @Test
    fun apiExceptionsMapToS3Codes() {
        assertEquals("NoSuchKey", ApiException.notFound("x").toS3Exception().code)
        assertEquals(HttpStatusCode.BadRequest, ApiException.tooLarge("x").toS3Exception().status)
        assertEquals("EntityTooLarge", ApiException.tooLarge("x").toS3Exception().code)
        // 唯一索引冲突是可重试的，不是 409：S3 客户端认 SlowDown 的退避语义
        val conflict = ApiException.conflict("NAME_CONFLICT", "x").toS3Exception()
        assertEquals("SlowDown", conflict.code)
        assertEquals(HttpStatusCode.ServiceUnavailable, conflict.status)
    }

    @Test
    fun doctypeIsRefused() = testApplication {
        setup()
        val r = client.post("/probe-xml") {
            setBody("""<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><r><Key>&x;</Key></r>""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertFalse(r.bodyAsText().contains("root:"), "外部实体绝不能展开")
    }

    @Test
    fun oversizedBodyIsRefused() = testApplication {
        setup()
        val r = client.post("/probe-xml") { setBody("<r><Key>" + "a".repeat(2 * 1024 * 1024) + "</Key></r>") }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }
}