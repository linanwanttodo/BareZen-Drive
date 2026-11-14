package com.linan.barezen_drive.s3

import com.linan.barezen_drive.storage.SigV4
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import java.security.MessageDigest

/**
 * aws-chunked 的三种分帧共用一个解码器。signingKey 为 null 即
 * STREAMING-UNSIGNED-PAYLOAD-TRAILER；expectTrailer 区分另外两种。
 *
 * 块签名在块末校验：数据边读边吐给下游，内存只有一个 64 KiB 缓冲。失败时
 * writer 以该异常关闭通道，下游 putChunk 立刻失败、路由 abort 会话——publish
 * 之前失败，绝不落半截文件（spec §3.4 的硬要求）。
 */
internal class AwsChunkedOptions(
    val decodedLength: Long,
    val signingKey: ByteArray?,
    val amzDate: String,
    val scope: String,
    val seedSignature: String,
    val expectTrailer: Boolean,
)

internal class AwsChunkedBody(val channel: ByteReadChannel, private val state: State) {
    /** 读完 channel 之后才有值；调用方在消费完 body 后核对校验和。 */
    val trailers: Map<String, String> get() = state.trailers

    internal class State {
        @Volatile
        var trailers: Map<String, String> = emptyMap()
    }
}

/** 一个块的上限。SDK 实际用 64 KiB–1 MiB；8 MiB 已经很宽，又不至于让声明长度失控。 */
private const val MAX_CHUNK_BYTES = 8L * 1024 * 1024
private const val MAX_HEADER_LINE = 1024
private val CHUNK_SCOPE = CoroutineScope(Dispatchers.IO + SupervisorJob())

internal fun awsChunked(source: ByteReadChannel, opts: AwsChunkedOptions): AwsChunkedBody {
    val state = AwsChunkedBody.State()
    val out = ByteChannel(autoFlush = true)
    CHUNK_SCOPE.launch {
        try {
            decode(source, opts, out, state)
            out.close()
        } catch (e: Throwable) {
            // 用 cancel(cause) 而不是让协程异常逃出去：下游要拿到原始 S3Exception
            // 才能渲染对应的 S3 错误码，包一层 ClosedByteChannelException 就丢了。
            out.cancel(e)
        }
    }
    return AwsChunkedBody(out, state)
}

private fun mismatch(message: String) =
    S3Exception("XAmzContentSHA256Mismatch", HttpStatusCode.BadRequest, message)

private suspend fun decode(
    source: ByteReadChannel,
    opts: AwsChunkedOptions,
    out: ByteWriteChannel,
    state: AwsChunkedBody.State,
) {
    var previous = opts.seedSignature
    var total = 0L
    val buf = ByteArray(64 * 1024)
    val crlf = ByteArray(2)
    while (true) {
        val line = source.readUTF8Line(MAX_HEADER_LINE) ?: throw mismatch("aws-chunked 体被截断")
        val size = line.substringBefore(';').trim().toLongOrNull(16) ?: throw mismatch("块长度非法")
        if (size < 0 || size > MAX_CHUNK_BYTES) throw mismatch("块长度超出上限")
        val chunkSig = if (opts.signingKey != null) {
            line.substringAfter(';', "").split(';')
                .firstOrNull { it.startsWith("chunk-signature=") }?.substringAfter('=')
                ?: throw mismatch("缺少 chunk-signature")
        } else null

        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = size
        while (remaining > 0) {
            val n = source.readAvailable(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (n <= 0) throw mismatch("aws-chunked 体被截断")
            digest.update(buf, 0, n)
            out.writeFully(buf, 0, n)
            remaining -= n
            total += n
            if (total > opts.decodedLength) throw mismatch("解码后长度超过 x-amz-decoded-content-length")
        }
        if (size > 0) {
            // 每个数据块后跟一个 CRLF；终止块没有（它的 CRLF 属于 header 行）。
            source.readFully(crlf, 0, 2)
            if (crlf[0] != '\r'.code.toByte() || crlf[1] != '\n'.code.toByte()) throw mismatch("块后缺少 CRLF")
        }
        if (opts.signingKey != null) {
            // digest() 出来的已经是 sha256，要的是它的十六进制，不能再哈希一遍。
            val dataHashHex = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            val expected = chunkSignature(opts, previous, dataHashHex)
            if (!MessageDigest.isEqual(expected.toByteArray(), chunkSig!!.toByteArray())) throw mismatch("块签名不符")
            previous = expected
        }
        if (size == 0L) break
    }
    if (total != opts.decodedLength) throw mismatch("解码后长度与 x-amz-decoded-content-length 不符")

    // 终止块之后：trailer 若干行 + 一个空行；无 trailer 时只有一个空行。
    val trailers = LinkedHashMap<String, String>()
    while (true) {
        val line = source.readUTF8Line(MAX_HEADER_LINE) ?: throw mismatch("aws-chunked 尾部被截断")
        if (line.isEmpty()) break
        if (!opts.expectTrailer) throw mismatch("非 trailer 分帧却带了尾部头")
        val colon = line.indexOf(':')
        if (colon <= 0) throw mismatch("尾部头格式非法")
        trailers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
    }
    if (opts.expectTrailer && opts.signingKey != null) {
        val sig = trailers.remove("x-amz-trailer-signature") ?: throw mismatch("缺少 x-amz-trailer-signature")
        val canonical = trailers.entries.sortedBy { it.key }.joinToString("") { (k, v) -> "$k:$v\n" }
        val expected = trailerSignature(opts, previous, canonical)
        if (!MessageDigest.isEqual(expected.toByteArray(), sig.toByteArray())) throw mismatch("尾部签名不符")
    }
    state.trailers = trailers
}

/** dataHashHex 已经是块数据的 sha256 十六进制，不能再哈希一遍。 */
private fun chunkSignature(opts: AwsChunkedOptions, previous: String, dataHashHex: String): String =
    SigV4.signatureHex(
        opts.signingKey!!,
        listOf(
            "AWS4-HMAC-SHA256-PAYLOAD", opts.amzDate, opts.scope, previous,
            SigV4.EMPTY_PAYLOAD_HASH, dataHashHex,
        ).joinToString("\n"),
    )

private fun trailerSignature(opts: AwsChunkedOptions, previous: String, canonical: String): String =
    SigV4.signatureHex(
        opts.signingKey!!,
        listOf(
            "AWS4-HMAC-SHA256-TRAILER", opts.amzDate, opts.scope, previous,
            SigV4.sha256Hex(canonical.toByteArray(Charsets.UTF_8)),
        ).joinToString("\n"),
    )

/**
 * Ktor 把通道的关闭原因包成 ClosedByteChannelException，原始异常在 cause 里。
 * 消费方（putChunk / 路由）必须剥掉这层才能按 S3Exception 渲染对应的错误码。
 */
internal fun Throwable.unwrapChannelCause(): Throwable {
    var current = this
    while (current is ClosedByteChannelException) current = current.cause ?: return current
    return current
}
