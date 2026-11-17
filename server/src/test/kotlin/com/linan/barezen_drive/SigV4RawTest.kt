package com.linan.barezen_drive

import com.linan.barezen_drive.storage.SigV4
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 入站规范化与出站编码是两件事，这里钉住入站那一半。
 *
 * 固定签名向量由独立实现（Python hmac/hashlib）算出，而不是用本项目的出站
 * 签名器自签自验——后者会让两边共享同一个 bug 时测试照样绿。
 */
class SigV4RawTest {
    @Test
    fun rawUriIsNotEncodedAgain() {
        assertEquals("/bucket/a%20b/c.txt", SigV4.canonicalUriRaw("/bucket/a%20b/c.txt"))
        assertEquals("/bucket/a+b", SigV4.canonicalUriRaw("/bucket/a+b"))
        assertEquals("/", SigV4.canonicalUriRaw(""))
        assertEquals("/", SigV4.canonicalUriRaw("/"))
        // 出站编码器会把 % 变成 %25，入站绝不能走它
        assertEquals("/bucket/a%2520b", SigV4.canonicalUri("/bucket/a%20b"))
    }

    @Test
    fun rawQueryKeepsEncodingAndSortsByEncodedName() {
        assertEquals(
            "list-type=2&prefix=a%20b&x-id=ListObjectsV2",
            SigV4.canonicalQueryRaw("x-id=ListObjectsV2&prefix=a%20b&list-type=2"),
        )
        // 未知参数必须计入：实测 rclone 会签 x-id
        assertEquals("a=1&a=2&b=", SigV4.canonicalQueryRaw("b=&a=2&a=1"))
        // 无值参数补等号
        assertEquals("delete=", SigV4.canonicalQueryRaw("delete"))
        assertEquals("", SigV4.canonicalQueryRaw(""))
    }

    @Test
    fun presignQueryDropsOnlyTheSignature() {
        assertEquals(
            "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-SignedHeaders=host",
            SigV4.canonicalQueryRaw(
                "X-Amz-Signature=abc&X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-SignedHeaders=host",
                dropSignature = true,
            ),
        )
    }

    @Test
    fun headerValuesTrimAndCollapseInnerWhitespace() {
        assertEquals(
            "host:example.com\nx-amz-meta-note:a b\n",
            SigV4.canonicalHeaders(
                mapOf("Host" to listOf(" example.com "), "X-Amz-Meta-Note" to listOf("  a \t b  ")),
                listOf("x-amz-meta-note", "host"),
            ),
        )
    }

    @Test
    fun multiValueHeadersJoinWithComma() {
        assertEquals(
            "x-amz-meta-a:1,2\n",
            SigV4.canonicalHeaders(mapOf("x-amz-meta-a" to listOf("1", " 2 ")), listOf("x-amz-meta-a")),
        )
    }

    @Test
    fun signingKeyAndSignatureMatchAnIndependentVector() {
        val key = SigV4.signingKey(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "20261028", "us-east-1", "s3",
        )
        assertEquals(
            "d3e38e2198006dda6b6bdf35d2c8e7aef4f4859e7cba3fad0f1ac7e868e0e255",
            key.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
        )
        val creq = SigV4.canonicalRequestRaw(
            "GET", "/testbucket", "list-type=2&prefix=a%20b&x-id=ListObjectsV2",
            "host:example.com\nx-amz-content-sha256:UNSIGNED-PAYLOAD\nx-amz-date:20261028T120000Z\n",
            "host;x-amz-content-sha256;x-amz-date", "UNSIGNED-PAYLOAD",
        )
        val sts = SigV4.stringToSign("20261028T120000Z", "20261028/us-east-1/s3/aws4_request", creq)
        assertEquals(
            "63b16ae6bb5bb9993410cb62d2a5e37b79251d0edfc9d33ed12d41fe7d0fcd25",
            SigV4.signatureHex(key, sts),
        )
    }
}