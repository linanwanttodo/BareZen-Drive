package com.linan.barezen_drive.storage

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * AWS Signature Version 4, implemented as pure functions so the algorithm can
 * be checked against the official test-suite vectors without any network.
 * Only the header set this provider actually sends is supported (host,
 * x-amz-date, x-amz-content-sha256) - a deliberate subset: no session tokens,
 * no extra signed headers.
 */
object SigV4 {
    const val EMPTY_PAYLOAD_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    /** RFC 3986 unreserved set; everything else percent-encoded with capital hex. */
    fun uriEncode(value: String, encodeSlash: Boolean = true): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(StandardCharsets.UTF_8)) {
            val c = b.toInt() and 0xff
            when {
                c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                    c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code -> sb.append(c.toChar())
                c == '/'.code && !encodeSlash -> sb.append('/')
                else -> sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    /** Encodes each path segment separately so slashes survive. */
    fun canonicalUri(path: String): String {
        if (path.isEmpty() || path == "/") return "/"
        return path.split('/').joinToString("/") { uriEncode(it, encodeSlash = false) }.let { if (it.startsWith("/")) it else "/$it" }
    }

    /** Encodes every key/value, then sorts by encoded key and value (spec). */
    fun canonicalQuery(query: Map<String, String>): String =
        query.entries.map { uriEncode(it.key, false) to uriEncode(it.value, false) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (k, v) -> "$k=$v" }

    /**
     * The canonical request, byte for byte as the spec defines it: method, URI,
     * query, one "key:value\n" line per signed header, a blank line, the
     * ";"-joined signed header names and the payload hash. Exposed because the
     * official AWS test suite ships this exact string per case, so the vector
     * test can compare it instead of only the signature that derives from it.
     */
    fun canonicalRequest(
        method: String,
        canonicalUri: String,
        canonicalQuery: String,
        headers: Map<String, String>,
        payloadHash: String,
    ): String {
        val sorted = toSortedHeaders(headers)
        val canonicalHeaders = sorted.entries.joinToString("") { (k, v) -> "$k:${v.trim()}\n" }
        val signedHeaders = sorted.keys.joinToString(";")
        return listOf(method, canonicalUri, canonicalQuery, canonicalHeaders, signedHeaders, payloadHash).joinToString("\n")
    }

    /**
     * Builds the Authorization header value. [headers] keys are matched
     * case-insensitively; they are lower-cased, trimmed and sorted exactly the
     * way the spec requires. [amzDate] is "yyyyMMdd'T'HHmmss'Z'", [dateStamp]
     * "yyyyMMdd" (UTC).
     */
    fun authorization(
        method: String,
        canonicalUri: String,
        canonicalQuery: String,
        headers: Map<String, String>,
        payloadHash: String,
        accessKey: String,
        secretKey: String,
        region: String,
        service: String,
        amzDate: String,
        dateStamp: String,
    ): String {
        val sorted = toSortedHeaders(headers)
        val canonicalRequest = canonicalRequest(method, canonicalUri, canonicalQuery, headers, payloadHash)
        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf("AWS4-HMAC-SHA256", amzDate, scope, sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8))).joinToString("\n")
        var key = ("AWS4$secretKey").toByteArray(StandardCharsets.UTF_8)
        for (part in listOf(dateStamp, region, service, "aws4_request")) key = hmac(key, part)
        val signature = hmac(key, stringToSign).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val signedHeaders = sorted.keys.joinToString(";")
        return "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    private fun toSortedHeaders(headers: Map<String, String>): Map<String, String> =
        headers.entries
            .associate { (k, v) -> k.lowercase() to v.trim().replace(Regex("[ \\t]+"), " ") }
            .toSortedMap()
}

/** Convenience: canonical URI of [uri]'s path with S3 single-encoding. */
fun URI.s3CanonicalUri(): String = SigV4.canonicalUri(rawPath ?: "/")
