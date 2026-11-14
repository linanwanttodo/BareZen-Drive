package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.db.WebdavTokensTable
import com.linan.barezen_drive.s3.S3Exception
import com.linan.barezen_drive.s3.respondS3Error
import com.linan.barezen_drive.s3.s3Authenticate
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.SigV4
import com.linan.barezen_drive.webdav.WebdavTokenService
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 用运行时签名而不是固定时间向量：签名端是 SigV4.authorization，它已被 AWS 官方
 * 套件逐字节钉住。时钟偏移靠"签一个 20 分钟前的时间"制造，不需要往生产代码里塞
 * 时钟接缝。
 */
class S3AuthTest {
    private val storageDir = Files.createTempDirectory("bz-s3auth").toString()
    private var accessKey = ""
    private var secret = ""
    private var userId = ""

    private suspend fun ApplicationTestBuilder.setup() {
        val cfg = AppConfig(
            0, "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30, registrationOpen = true,
        )
        application {
            module(cfg, LocalStorageProvider(Path.of(cfg.storageDir)))
            routing {
                get("/probe") {
                    val rid = UUID.randomUUID().toString()
                    try {
                        val token = call.s3Authenticate()
                        call.response.header("x-amz-request-id", rid)
                        call.respondText(token.userId.toString())
                    } catch (e: S3Exception) {
                        call.respondS3Error(e, rid)
                    }
                }
            }
        }
        client.post("/api/auth/register") {
            setBody("""{"username":"user1","password":"password123"}""")
            header(HttpHeaders.ContentType, "application/json")
        }
        val login = client.post("/api/auth/login") {
            setBody("""{"username":"user1","password":"password123"}""")
            header(HttpHeaders.ContentType, "application/json")
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
            UsersTable.selectAll().where { UsersTable.username eq "user1" }.single()[UsersTable.id].toString()
        }
    }

    private fun amzDate(offsetSeconds: Long = 0): String =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
            .format(Instant.now().plusSeconds(offsetSeconds))

    /** 用出站签名器给一个 GET /probe 签名；headers 必须含 host。 */
    private fun signedHeaders(
        date: String = amzDate(),
        payloadHash: String = SigV4.EMPTY_PAYLOAD_HASH,
        extra: Map<String, String> = emptyMap(),
        secretOverride: String = secret,
        accessKeyOverride: String = accessKey,
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["host"] = "localhost"
        headers["x-amz-date"] = date
        headers["x-amz-content-sha256"] = payloadHash
        headers.putAll(extra)
        val authz = SigV4.authorization(
            method = "GET", canonicalUri = "/probe", canonicalQuery = "",
            headers = headers, payloadHash = payloadHash,
            accessKey = accessKeyOverride, secretKey = secretOverride,
            region = "us-east-1", service = "s3",
            amzDate = date, dateStamp = date.substring(0, 8),
        )
        return headers + (HttpHeaders.Authorization to authz)
    }

    private suspend fun ApplicationTestBuilder.probe(headers: Map<String, String>) =
        client.get("/probe") { headers.forEach { (k, v) -> header(k, v) } }

    @Test
    fun aCorrectlySignedRequestAuthenticates() = testApplication {
        setup()
        val r = probe(signedHeaders())
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(userId, r.bodyAsText())
    }

    @Test
    fun aWrongSecretIsSignatureDoesNotMatch() = testApplication {
        setup()
        val r = probe(signedHeaders(secretOverride = "f".repeat(64)))
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("<Code>SignatureDoesNotMatch</Code>"), r.bodyAsText())
    }

    @Test
    fun anUnknownAccessKeyIsInvalidAccessKeyId() = testApplication {
        setup()
        val r = probe(signedHeaders(accessKeyOverride = UUID.randomUUID().toString()))
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidAccessKeyId</Code>"), r.bodyAsText())
    }

    @Test
    fun aNonUuidAccessKeyIsInvalidAccessKeyId() = testApplication {
        setup()
        val r = probe(signedHeaders(accessKeyOverride = "AKIDEXAMPLE"))
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidAccessKeyId</Code>"), r.bodyAsText())
    }

    @Test
    fun aSkewedClockIsRequestTimeTooSkewed() = testApplication {
        setup()
        val r = probe(signedHeaders(date = amzDate(-1200)))
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("<Code>RequestTimeTooSkewed</Code>"), r.bodyAsText())
    }

    @Test
    fun unsignedRequestsAreAccessDenied() = testApplication {
        setup()
        val r = client.get("/probe")
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("<Code>AccessDenied</Code>"), r.bodyAsText())
    }

    @Test
    fun aTokenWithoutTheEncryptedCopyIsAccessDenied() = testApplication {
        setup()
        transaction(DatabaseFactory.db) {
            WebdavTokensTable.update({ WebdavTokensTable.id eq UUID.fromString(accessKey) }) {
                it[WebdavTokensTable.secretEnc] = null
            }
        }
        val r = probe(signedHeaders())
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("<Code>AccessDenied</Code>"), r.bodyAsText())
        assertTrue(r.bodyAsText().contains("重新签发"), r.bodyAsText())
    }

    @Test
    fun aRevokedTokenIsInvalidAccessKeyId() = testApplication {
        setup()
        WebdavTokenService.revoke(UUID.fromString(userId), UUID.fromString(accessKey))
        val r = probe(signedHeaders())
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidAccessKeyId</Code>"), r.bodyAsText())
    }

    @Test
    fun extraSignedHeadersAreHonoured() = testApplication {
        setup()
        // 实测 rclone 会签 amz-sdk-invocation-id / x-amz-meta-mtime 这类头
        val r = probe(signedHeaders(extra = mapOf("x-amz-meta-mtime" to "1700000000.000", "amz-sdk-invocation-id" to "abc")))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    }

    @Test
    fun aSecurityTokenIsRejected() = testApplication {
        setup()
        val r = client.get("/probe") {
            signedHeaders().forEach { (k, v) -> header(k, v) }
            header("x-amz-security-token", "sts-token")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun failuresAreThrottledAsSlowDown() = testApplication {
        setup()
        var last = ""
        repeat(25) { last = probe(signedHeaders(secretOverride = "f".repeat(64))).bodyAsText() }
        assertTrue(last.contains("<Code>SlowDown</Code>"), last)
    }

    @Test
    fun successesDoNotBurnTheAuthBudget() = testApplication {
        setup()
        repeat(30) {
            val r = probe(signedHeaders())
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        }
    }

    /** 预签名签名与内容 URL 的 sig 都是 bearer 级秘密，进访问日志等于交给任何能读日志的人。 */
    @Test
    fun theAccessLogRedactsBothSignatureForms() = testApplication {
        setup()
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            client.get("/probe?sig=SECRET-SIG&X-Amz-Signature=SECRET-AMZ") {
                signedHeaders().forEach { (k, v) -> header(k, v) }
            }
            val lines = appender.list.map { it.formattedMessage }
            val line = lines.firstOrNull { it.contains("/probe") }
            assertNotNull(line, "访问日志里没有 /probe 这一行: $lines")
            assertTrue(line.contains("sig=***"), line)
            assertTrue(line.contains("X-Amz-Signature=***"), line)
            assertFalse(line.contains("SECRET-SIG"), line)
            assertFalse(line.contains("SECRET-AMZ"), line)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}