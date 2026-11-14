package com.linan.barezen_drive.s3

import com.linan.barezen_drive.storage.SigV4
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

const val S3_UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
private const val ALGORITHM = "AWS4-HMAC-SHA256"
private val AMZ_DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
private val HTTP_DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)

/** Authorization 头里的三段。region 任意接受：本项目没有区域概念。 */
data class S3HeaderAuth(
    val accessKey: String, val dateStamp: String, val region: String,
    val signedHeaders: List<String>, val signature: String,
)

/** 预签名 query 里的参数。 */
data class S3PresignAuth(
    val accessKey: String, val dateStamp: String, val region: String,
    val amzDate: String, val expires: Long, val signedHeaders: List<String>, val signature: String,
)

object S3SigV4 {
    /** 形状校验：三段齐、SignedHeaders 含 host、signature 是 64 位小写 hex、service=s3、终止段 aws4_request。 */
    fun parseAuthorization(value: String): S3HeaderAuth? {
        if (!value.startsWith("$ALGORITHM ")) return null
        val fields = value.removePrefix("$ALGORITHM ").split(',')
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq < 0) null else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
            }.toMap()
        val credential = fields["Credential"]?.split('/') ?: return null
        if (credential.size != 5) return null
        val (accessKey, dateStamp, region, service, terminator) = credential
        if (service != "s3" || terminator != "aws4_request") return null
        if (dateStamp.length != 8 || dateStamp.any { !it.isDigit() }) return null
        val signed = fields["SignedHeaders"]?.split(';')?.map { it.lowercase() } ?: return null
        if ("host" !in signed || signed.isEmpty()) return null
        val signature = fields["Signature"] ?: return null
        if (!Regex("^[0-9a-f]{64}$").matches(signature)) return null
        return S3HeaderAuth(accessKey, dateStamp, region, signed, signature)
    }

    fun parsePresign(rawQuery: String): S3PresignAuth? {
        val params = rawQuery.split('&').filter { it.isNotEmpty() }.map {
            val eq = it.indexOf('=')
            if (eq < 0) it to "" else it.substring(0, eq) to it.substring(eq + 1)
        }
        fun get(name: String): String? = params.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
        if (get("X-Amz-Algorithm") != ALGORITHM) return null
        val credential = get("X-Amz-Credential")?.split('/') ?: return null
        if (credential.size != 5) return null
        val (accessKey, dateStamp, region, service, terminator) = credential
        if (service != "s3" || terminator != "aws4_request") return null
        val amzDate = get("X-Amz-Date") ?: return null
        if (parseAmzDate(amzDate) == null) return null
        val expires = get("X-Amz-Expires")?.toLongOrNull() ?: return null
        val signed = get("X-Amz-SignedHeaders")?.split(';')?.map { it.lowercase() } ?: return null
        if ("host" !in signed) return null
        val signature = get("X-Amz-Signature") ?: return null
        if (!Regex("^[0-9a-f]{64}$").matches(signature)) return null
        return S3PresignAuth(accessKey, dateStamp, region, amzDate, expires, signed, signature)
    }

    fun parseAmzDate(value: String): Long? =
        runCatching { Instant.from(AMZ_DATE_FMT.parse(value)).toEpochMilli() }.getOrNull()

    fun parseHttpDate(value: String): Long? =
        runCatching { Instant.from(HTTP_DATE_FMT.parse(value)).toEpochMilli() }.getOrNull()

    /** 签名用的时间：x-amz-date 优先，退回 Date 头；都没有则 null（调用方判 403）。 */
    fun amzDateOf(request: ApplicationRequest): String? {
        request.headers["x-amz-date"]?.let { return it }
        val date = request.headers["Date"] ?: return null
        val millis = parseHttpDate(date) ?: return null
        return AMZ_DATE_FMT.format(Instant.ofEpochMilli(millis))
    }

    fun payloadHashOf(request: ApplicationRequest, presign: S3PresignAuth?): String {
        request.headers["x-amz-content-sha256"]?.let { return it }
        if (presign != null) {
            val raw = request.queryString()
            val v = raw.split('&').firstOrNull { it.substringBefore('=').equals("X-Amz-Content-Sha256", ignoreCase = true) }
            if (v != null) return v.substringAfter('=', S3_UNSIGNED_PAYLOAD)
        }
        return S3_UNSIGNED_PAYLOAD
    }

    /**
     * 规范化 + 派生 + 比对。返回 null 表示通过，否则返回要抛的 S3Exception。
     * [secret] 是明文令牌；[amzDate] 是签名端用的时间串。
     */
    fun verify(
        request: ApplicationRequest,
        accessKey: String,
        secret: String,
        region: String,
        dateStamp: String,
        amzDate: String,
        signedHeaders: List<String>,
        providedSignature: String,
        presign: Boolean,
    ): S3Exception? {
        val payloadHash = payloadHashOf(request, if (presign) parsePresign(request.queryString()) else null)
        val headers = LinkedHashMap<String, List<String>>()
        request.headers.forEach { name, values -> headers[name.lowercase()] = values }
        val headerBlock = SigV4.canonicalHeaders(headers, signedHeaders)
        val creq = SigV4.canonicalRequestRaw(
            request.httpMethod.value,
            SigV4.canonicalUriRaw(request.path()),
            SigV4.canonicalQueryRaw(request.queryString(), dropSignature = presign),
            headerBlock,
            signedHeaders.map { it.lowercase() }.distinct().sorted().joinToString(";"),
            payloadHash,
        )
        val scope = "$dateStamp/$region/s3/aws4_request"
        val expected = SigV4.signatureHex(
            SigV4.signingKey(secret, dateStamp, region, "s3"),
            SigV4.stringToSign(amzDate, scope, creq),
        )
        // 常量时间比较：逐字节短路会泄露前缀匹配长度。
        if (!MessageDigest.isEqual(expected.toByteArray(), providedSignature.toByteArray())) {
            return S3Exception("SignatureDoesNotMatch", HttpStatusCode.Forbidden, "签名不符")
        }
        return null
    }
}