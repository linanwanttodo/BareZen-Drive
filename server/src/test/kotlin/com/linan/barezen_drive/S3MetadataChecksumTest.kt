package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.s3.S3Exception
import com.linan.barezen_drive.s3.respondS3Error
import com.linan.barezen_drive.s3.s3ChecksumHeader
import com.linan.barezen_drive.s3.s3Checksums
import com.linan.barezen_drive.s3.s3CompositeChecksum
import com.linan.barezen_drive.s3.s3ContentMd5
import com.linan.barezen_drive.s3.s3MetaHeaders
import com.linan.barezen_drive.s3.s3UserMeta
import com.linan.barezen_drive.s3.s3VerifyChecksums
import com.linan.barezen_drive.s3.s3VerifyContentMd5
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class S3MetadataChecksumTest {
    private fun ApplicationTestBuilder.setup() {
        val dir = Files.createTempDirectory("bz-s3meta").toString()
        val cfg = AppConfig(
            0, "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", dir, 1L shl 30, registrationOpen = true,
        )
        application {
            module(cfg, LocalStorageProvider(Path.of(cfg.storageDir)))
            routing {
                // 路由自己 catch：S3Exception 落到 StatusPages 会变成 500 JSON，
                // 那就测不到 S3 的错误码了。
                get("/meta") {
                    val rid = UUID.randomUUID().toString()
                    try {
                        call.respondText(call.s3UserMeta() ?: "null")
                    } catch (e: S3Exception) {
                        call.respondS3Error(e, rid)
                    }
                }
                get("/checksums") {
                    val rid = UUID.randomUUID().toString()
                    try {
                        val cs = call.s3Checksums()
                        call.respondText(cs.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" })
                    } catch (e: S3Exception) {
                        call.respondS3Error(e, rid)
                    }
                }
                get("/md5") { call.respondText(call.s3ContentMd5() ?: "null") }
            }
        }
    }

    private suspend fun ApplicationTestBuilder.meta(vararg headers: Pair<String, String>): HttpResponse =
        client.get("/meta") { headers.forEach { (k, v) -> header(k, v) } }

    private fun crc32Raw(value: Long): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    @Test
    fun metadataIsLowercasedAndRoundTrips() = testApplication {
        setup()
        val r = meta("x-amz-meta-mtime" to "1700000000.000", "X-Amz-Meta-Md5ChkSum" to "abc")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(
            mapOf("mtime" to "1700000000.000", "md5chksum" to "abc"),
            s3MetaHeaders(r.bodyAsText()),
        )
    }

    @Test
    fun noMetadataIsNullSoAnOverwriteClearsIt() = testApplication {
        setup()
        assertEquals("null", client.get("/meta").bodyAsText())
        assertTrue(s3MetaHeaders(null).isEmpty())
    }

    @Test
    fun tooManyKeysIsMetadataTooLarge() = testApplication {
        setup()
        val headers = (0 until 17).map { "x-amz-meta-k$it" to "v" }.toTypedArray()
        val r = meta(*headers)
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>MetadataTooLarge</Code>"), r.bodyAsText())
        assertTrue(r.bodyAsText().contains("键过多"), r.bodyAsText())
        // 反向对照：16 键必须收，否则上一条测的是"有键就拒"
        val ok = meta(*(0 until 16).map { "x-amz-meta-k$it" to "v" }.toTypedArray())
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals(16, s3MetaHeaders(ok.bodyAsText()).size)
    }

    @Test
    fun crc64nvmeIsRefused() = testApplication {
        setup()
        val r = client.get("/checksums") { header("x-amz-checksum-crc64nvme", "AAAA") }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>InvalidRequest</Code>"), r.bodyAsText())
    }

    @Test
    fun supportedChecksumsAreCollected() = testApplication {
        setup()
        val r = client.get("/checksums") {
            header("x-amz-checksum-crc32", "DUoRhQ==")
            header("x-amz-checksum-sha256", "AAAA")
            header("x-amz-checksum-crc32c", "yZRlqg==")
            header("x-amz-checksum-sha1", "qvTGHdzF6KLavt4PO0gs2a6pQ00=")
            header("x-amz-unknown", "ignored")
        }
        assertEquals(
            "CRC32=DUoRhQ==,CRC32C=yZRlqg==,SHA1=qvTGHdzF6KLavt4PO0gs2a6pQ00=,SHA256=AAAA",
            r.bodyAsText(),
        )
    }

    @Test
    fun checksumVerificationComparesBase64OfTheRawDigest() {
        val raw = crc32Raw(CRC32().apply { update("hello world".toByteArray()) }.value)
        s3VerifyChecksums(mapOf("CRC32" to raw), mapOf("CRC32" to "DUoRhQ=="))
        val e = assertFailsWith<S3Exception> {
            s3VerifyChecksums(mapOf("CRC32" to raw), mapOf("CRC32" to "AAAAAA=="))
        }
        assertEquals("BadDigest", e.code)
        assertTrue(e.message!!.contains("CRC32"), e.message)
    }

    @Test
    fun aMissingComputedDigestIsBadDigest() {
        val e = assertFailsWith<S3Exception> {
            s3VerifyChecksums(emptyMap(), mapOf("SHA256" to "AAAA"))
        }
        assertEquals("BadDigest", e.code)
        assertTrue(e.message!!.contains("缺少 SHA256"), e.message)
    }

    @Test
    fun contentMd5IsCheckedWhenPresent() {
        val md5 = MessageDigest.getInstance("MD5").digest("hello".toByteArray())
        s3VerifyContentMd5(md5, "XUFAKrxLKna5cZ2REBfFkg==")
        s3VerifyContentMd5(md5, null)
        val e = assertFailsWith<S3Exception> { s3VerifyContentMd5(md5, "AAAA") }
        assertEquals("BadDigest", e.code)
    }

    @Test
    fun contentMd5HeaderIsReadAndTrimmed() = testApplication {
        setup()
        assertEquals("null", client.get("/md5").bodyAsText())
        val r = client.get("/md5") { header("Content-MD5", "  XUFAKrxLKna5cZ2REBfFkg==  ") }
        assertEquals("XUFAKrxLKna5cZ2REBfFkg==", r.bodyAsText())
    }

    /**
     * 四个算法的期望值由独立的 Python zlib/hashlib 与手写 CRC32C（反射多项式
     * 0x82F63B78，对 "123456789" 得 0xE3069283）算出，不迁就实现。
     */
    @Test
    fun compositeChecksumConcatenatesThenDigestsAndAppendsCount() {
        val a = byteArrayOf(0, 0, 0, 1)
        val b = byteArrayOf(0, 0, 0, 2)
        assertEquals("tkyX9Q==-2", s3CompositeChecksum("CRC32", listOf(a, b)))
        assertEquals("VQKt0Q==-2", s3CompositeChecksum("CRC32C", listOf(a, b)))
        assertEquals("WUuvpMDsLr0LmazZgz8bWWb8//U=-2", s3CompositeChecksum("SHA1", listOf(a, b)))
        assertEquals("D1hd1RjtBkTz7f0/fVASzJ9EXEydJOFo5pTE1vNvrqY=-2", s3CompositeChecksum("SHA256", listOf(a, b)))
    }

    /** CRC32C 与 CRC32 只差多项式：同一份数据必须得出不同的值，否则是拿错了类。 */
    @Test
    fun crc32cIsCastagnoliNotIeee() {
        val data = "hello world".toByteArray()
        assertEquals("DUoRhQ==-1", s3CompositeChecksum("CRC32", listOf(data)))
        assertEquals("yZRlqg==-1", s3CompositeChecksum("CRC32C", listOf(data)))
    }

    @Test
    fun checksumHeaderNamesAreTheLowercasedAlgorithm() {
        assertEquals("x-amz-checksum-crc32", s3ChecksumHeader("CRC32"))
        assertEquals("x-amz-checksum-crc32c", s3ChecksumHeader("CRC32C"))
        assertEquals("x-amz-checksum-sha1", s3ChecksumHeader("SHA1"))
        assertEquals("x-amz-checksum-sha256", s3ChecksumHeader("SHA256"))
    }

    @Test
    fun metadataKeyLimitIsCharsAndValueLimitIsBytes() = testApplication {
        setup()
        assertEquals(HttpStatusCode.OK, meta("x-amz-meta-" + "k".repeat(64) to "v").status)
        val longKey = meta("x-amz-meta-" + "k".repeat(65) to "v")
        assertEquals(HttpStatusCode.BadRequest, longKey.status)
        assertTrue(longKey.bodyAsText().contains("键超长"), longKey.bodyAsText())

        assertEquals(HttpStatusCode.OK, meta("x-amz-meta-k" to "v".repeat(512)).status)
        val longValue = meta("x-amz-meta-k" to "v".repeat(513))
        assertEquals(HttpStatusCode.BadRequest, longValue.status)
        assertTrue(longValue.bodyAsText().contains("值超长"), longValue.bodyAsText())

        // 值上限按字节：170 个中文字 510 字节收，171 个 513 字节拒，字符数都没超
        assertEquals(HttpStatusCode.OK, meta("x-amz-meta-k" to "中".repeat(170)).status)
        assertEquals(HttpStatusCode.BadRequest, meta("x-amz-meta-k" to "中".repeat(171)).status)
    }

    @Test
    fun metadataTotalJsonLimitIsEnforced() = testApplication {
        setup()
        // 16 键（正好等于上限）、每键 64 字符、每值 512 字节：单条都合法，总量超 4096。
        // 后缀必须补零到两位，否则 k*63+10 是 65 字符，先撞键长上限就测不到总量了。
        val headers = (0 until 16)
            .map { "x-amz-meta-" + "k".repeat(62) + "%02d".format(it) to "v".repeat(512) }
            .toTypedArray()
        val r = meta(*headers)
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("<Code>MetadataTooLarge</Code>"), r.bodyAsText())
        assertTrue(r.bodyAsText().contains("总量超限"), r.bodyAsText())
    }

    @Test
    fun metaHeaderNamesAreCaseInsensitive() = testApplication {
        setup()
        val r = meta("X-Amz-Meta-Foo" to "a", "x-amz-meta-foo" to "b")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val parsed = s3MetaHeaders(r.bodyAsText())
        // 只钉"合成一个键"：Ktor 的头存储可能合并成多值也可能保留两条同名条目，
        // 两种都必须收敛成一个 foo，具体取哪个值不是本服务能决定的。
        assertEquals(1, parsed.size, r.bodyAsText())
        assertTrue(parsed.containsKey("foo"), r.bodyAsText())
    }

    @Test
    fun corruptStoredMetadataReadsAsNoMetadata() {
        assertTrue(s3MetaHeaders(null).isEmpty())
        assertTrue(s3MetaHeaders("").isEmpty())
        assertTrue(s3MetaHeaders("   ").isEmpty())
        assertTrue(s3MetaHeaders("not json").isEmpty())
        assertTrue(s3MetaHeaders("[1,2]").isEmpty())
        assertEquals(mapOf("a" to "b"), s3MetaHeaders("""{"a":"b"}"""))
    }
}