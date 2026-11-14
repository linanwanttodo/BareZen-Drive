package com.linan.barezen_drive

import com.linan.barezen_drive.s3.AwsChunkedOptions
import com.linan.barezen_drive.s3.S3Exception
import com.linan.barezen_drive.s3.awsChunked
import com.linan.barezen_drive.s3.unwrapChannelCause
import com.linan.barezen_drive.storage.SigV4
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.runBlocking
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 签名链常量全部由独立的 Python hmac/hashlib 算出，不是用本项目的签名器自签自验：
 * 自签自验会让 KDF 或签名串的错误两边一起错，测试照样绿。
 */
class AwsChunkedChannelTest {
    private val secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val amzDate = "20261028T120000Z"
    private val scope = "20261028/us-east-1/s3/aws4_request"
    private val seed = "7d228d6d6634b47a27e072bf241bf73fc5df9097370de041b1b0d7739c317cfb"
    private val sig1 = "0496f375c466b8dd5a023ff514f1f468c40415264c0c33a53b60026cb3788863"
    private val sig2 = "d679aee27d55289bddff785882956b06aaa34feaa21aa62e344b650a1b9ed5a6"
    private val sigZero = "980c40882bdf21a8b7d744a93194f9be9911a338339cc7f7f2b531e7f455de50"
    private val trailerSig = "089fdc678f715de9f9fa05cd7787d4af0a034dc2bbefc0d9686c7bcf1c227f41"
    private val sigEmptyObject = "7567b5692abdb436ed4d18c7fbf57ec26b9c140927a7f06a1650c407fbf42d2a"

    private fun key() = SigV4.signingKey(secret, "20261028", "us-east-1", "s3")

    private fun opts(trailer: Boolean, signed: Boolean = true, length: Long = 11) = AwsChunkedOptions(
        decodedLength = length,
        signingKey = if (signed) key() else null,
        amzDate = amzDate, scope = scope, seedSignature = if (signed) seed else "",
        expectTrailer = trailer,
    )

    private suspend fun drain(body: ByteReadChannel): String =
        try {
            body.readRemaining().readByteArray().toString(Charsets.UTF_8)
        } catch (e: ClosedByteChannelException) {
            throw e.unwrapChannelCause()
        }

    private fun signedFrames() =
        "6;chunk-signature=$sig1\r\nhello \r\n" +
        "5;chunk-signature=$sig2\r\nworld\r\n" +
        "0;chunk-signature=$sigZero\r\n\r\n"

    @Test
    fun signedChunksDecodeToTheContent() = runBlocking {
        val body = awsChunked(ByteReadChannel(signedFrames().toByteArray()), opts(trailer = false))
        assertEquals("hello world", drain(body.channel))
    }

    @Test
    fun unsignedTrailerFramesDecodeAndExposeTrailers() = runBlocking {
        val framed = "6\r\nhello \r\n5\r\nworld\r\n0\r\nx-amz-checksum-crc32:DUoRhQ==\r\n\r\n"
        val body = awsChunked(ByteReadChannel(framed.toByteArray()), opts(trailer = true, signed = false))
        assertEquals("hello world", drain(body.channel))
        assertEquals("DUoRhQ==", body.trailers["x-amz-checksum-crc32"])
    }

    @Test
    fun signedTrailerFramesVerifyTheTrailerSignature() = runBlocking {
        val framed = "6;chunk-signature=$sig1\r\nhello \r\n" +
            "5;chunk-signature=$sig2\r\nworld\r\n" +
            "0;chunk-signature=$sigZero\r\n" +
            "x-amz-checksum-crc32:AAAAAA==\r\n" +
            "x-amz-trailer-signature:$trailerSig\r\n\r\n"
        val body = awsChunked(ByteReadChannel(framed.toByteArray()), opts(trailer = true))
        assertEquals("hello world", drain(body.channel))
        assertEquals("AAAAAA==", body.trailers["x-amz-checksum-crc32"])
    }

    @Test
    fun aTamperedChunkSignatureFails() = runBlocking {
        val bad = signedFrames().replace(sig1, "0".repeat(64))
        val body = awsChunked(ByteReadChannel(bad.toByteArray()), opts(trailer = false))
        val e = assertFailsWith<S3Exception> { drain(body.channel) }
        assertEquals("XAmzContentSHA256Mismatch", e.code)
    }

    @Test
    fun aTamperedTrailerSignatureFails() = runBlocking<Unit> {
        val framed = "6;chunk-signature=$sig1\r\nhello \r\n5;chunk-signature=$sig2\r\nworld\r\n" +
            "0;chunk-signature=$sigZero\r\nx-amz-checksum-crc32:AAAAAA==\r\n" +
            "x-amz-trailer-signature:${"0".repeat(64)}\r\n\r\n"
        val body = awsChunked(ByteReadChannel(framed.toByteArray()), opts(trailer = true))
        assertFailsWith<S3Exception> { drain(body.channel) }
    }

    @Test
    fun aLengthMismatchFails() = runBlocking {
        val body = awsChunked(ByteReadChannel(signedFrames().toByteArray()), opts(trailer = false, length = 12))
        val e = assertFailsWith<S3Exception> { drain(body.channel) }
        assertEquals("XAmzContentSHA256Mismatch", e.code)
    }

    @Test
    fun aTruncatedBodyFails() = runBlocking<Unit> {
        val body = awsChunked(ByteReadChannel("6;chunk-signature=$sig1\r\nhel".toByteArray()), opts(trailer = false))
        assertFailsWith<S3Exception> { drain(body.channel) }
    }

    @Test
    fun anOversizedChunkIsRefused() = runBlocking<Unit> {
        val framed = "${(9L * 1024 * 1024).toString(16)};chunk-signature=$sig1\r\n"
        val body = awsChunked(ByteReadChannel(framed.toByteArray()), opts(trailer = false, length = 9L * 1024 * 1024))
        val e = assertFailsWith<S3Exception> { drain(body.channel) }
        // 只断言"抛了 S3Exception"会被"体被截断"满足（源里只有 header 行），
        // 等于没测上限：必须钉住是上限这条规则拒绝的。
        assertTrue(e.message!!.contains("上限"), e.message)
    }

    @Test
    fun aEmptyObjectDecodes() = runBlocking {
        val framed = "0;chunk-signature=$sigEmptyObject\r\n\r\n"
        val body = awsChunked(ByteReadChannel(framed.toByteArray()), opts(trailer = false, length = 0))
        assertEquals("", drain(body.channel))
    }

    @Test
    fun aTrailerOnNonTrailerFramingIsRefused() = runBlocking<Unit> {
        val framed = "6\r\nhello \r\n5\r\nworld\r\n0\r\nx-amz-checksum-crc32:DUoRhQ==\r\n\r\n"
        val body = awsChunked(ByteReadChannel(framed.toByteArray()), opts(trailer = false, signed = false))
        val e = assertFailsWith<S3Exception> { drain(body.channel) }
        assertEquals("XAmzContentSHA256Mismatch", e.code)
    }

    @Test
    fun aSignedTrailerWithoutItsSignatureIsRefused() = runBlocking<Unit> {
        val framed = "6;chunk-signature=$sig1\r\nhello \r\n5;chunk-signature=$sig2\r\nworld\r\n" +
            "0;chunk-signature=$sigZero\r\nx-amz-checksum-crc32:AAAAAA==\r\n\r\n"
        val body = awsChunked(ByteReadChannel(framed.toByteArray()), opts(trailer = true))
        val e = assertFailsWith<S3Exception> { drain(body.channel) }
        // 同理：容忍缺失签名时会走到"尾部签名不符"，也是 S3Exception，
        // 不钉住消息就测不出"缺少签名头"这条规则。
        assertTrue(e.message!!.contains("缺少 x-amz-trailer-signature"), e.message)
    }
}
