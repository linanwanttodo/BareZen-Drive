package com.linan.barezen_drive.s3

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Checksum

internal val S3_DIGEST_ALGORITHMS = setOf("CRC32", "CRC32C", "SHA1", "SHA256", "MD5")
private val HEADER_ALGORITHMS = mapOf(
    "x-amz-checksum-crc32" to "CRC32",
    "x-amz-checksum-crc32c" to "CRC32C",
    "x-amz-checksum-sha1" to "SHA1",
    "x-amz-checksum-sha256" to "SHA256",
)

internal fun s3ChecksumHeader(algorithm: String): String = "x-amz-checksum-" + algorithm.lowercase()

/** 请求侧声明的校验和。CRC64NVME 不支持：JDK 没有它，spec §7.1 明确拒。 */
internal fun ApplicationCall.s3Checksums(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    request.headers.forEach { name, values ->
        val lower = name.lowercase()
        if (lower == "x-amz-checksum-crc64nvme") {
            throw S3Exception("InvalidRequest", HttpStatusCode.BadRequest, "不支持 CRC64NVME")
        }
        val algorithm = HEADER_ALGORITHMS[lower] ?: return@forEach
        out[algorithm] = values.joinToString(",").trim()
    }
    return out
}

internal fun ApplicationCall.s3ContentMd5(): String? = request.headers["Content-MD5"]?.trim()

/** computed 是算法名 → 原始摘要字节；expected 是算法名 → base64。 */
internal fun s3VerifyChecksums(computed: Map<String, ByteArray>, expected: Map<String, String>) {
    for ((algorithm, want) in expected) {
        val raw = computed[algorithm]
            ?: throw S3Exception("BadDigest", HttpStatusCode.BadRequest, "缺少 $algorithm 摘要")
        val got = Base64.getEncoder().encodeToString(raw)
        if (!MessageDigest.isEqual(got.toByteArray(), want.toByteArray())) {
            throw S3Exception("BadDigest", HttpStatusCode.BadRequest, "$algorithm 校验和不符")
        }
    }
}

internal fun s3VerifyContentMd5(md5Raw: ByteArray, expected: String?) {
    if (expected == null) return
    val got = Base64.getEncoder().encodeToString(md5Raw)
    if (!MessageDigest.isEqual(got.toByteArray(), expected.toByteArray())) {
        throw S3Exception("BadDigest", HttpStatusCode.BadRequest, "Content-MD5 不符")
    }
}

/** S3 的复合校验和：各 part 的原始摘要连接后再取一次摘要，base64，尾部 -N。 */
internal fun s3CompositeChecksum(algorithm: String, partRaw: List<ByteArray>): String {
    val raw = when (algorithm) {
        "CRC32" -> crc32(partRaw, CRC32())
        "CRC32C" -> crc32(partRaw, castagnoli())
        else -> {
            val md = MessageDigest.getInstance(jdkName(algorithm))
            partRaw.forEach { md.update(it) }
            md.digest()
        }
    }
    return Base64.getEncoder().encodeToString(raw) + "-" + partRaw.size
}

internal fun jdkName(algorithm: String): String = when (algorithm) {
    "SHA1" -> "SHA-1"
    "SHA256" -> "SHA-256"
    "MD5" -> "MD5"
    else -> throw IllegalArgumentException("not a MessageDigest algorithm: $algorithm")
}

/** JDK 没有 CRC32C 的公开类，但 java.util.zip.CRC32C 自 JDK 9 起存在。 */
internal fun castagnoli(): Checksum = java.util.zip.CRC32C()

private fun crc32(parts: List<ByteArray>, checksum: Checksum): ByteArray {
    parts.forEach { checksum.update(it) }
    val v = checksum.value
    return byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}