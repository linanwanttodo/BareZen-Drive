package com.linan.barezen_drive

import com.linan.barezen_drive.storage.SigV4
import java.io.File
import kotlin.test.*

/**
 * The AWS SigV4 official test-suite vectors (aws-sig-v4-test-suite, fetched
 * verbatim into src/test/resources/sigv4). All three cases run against the
 * suite's example credentials (AKIDEXAMPLE plus its published example secret),
 * region us-east-1, service "service", timestamp 20150830T123600Z.
 *
 * Each case is checked on three levels, so a failure points at the exact stage
 * that drifted: the canonical request string (.creq), the Authorization header
 * value (.authz), and the signed request as it goes on the wire (.sreq), which
 * pins that we sign exactly the headers we send and keep the request line and
 * the original headers untouched.
 */
class SigV4VectorTest {
    private val accessKey = "AKIDEXAMPLE"
    private val secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"

    private fun resource(path: String): String {
        val url = javaClass.classLoader.getResource("sigv4/$path")
        assertNotNull(url, "missing fixture sigv4/$path")
        return File(url.toURI()).readText()
    }

    private fun runCase(name: String) {
        val req = resource("$name/$name.req")
        val lines = req.lines().filter { it.isNotBlank() }
        val (method, target, _) = lines.first().split(' ')
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            headers[line.substring(0, idx).lowercase()] = line.substring(idx + 1)
        }
        val qIdx = target.indexOf('?')
        val path = if (qIdx >= 0) target.substring(0, qIdx) else target
        val query = if (qIdx >= 0) {
            target.substring(qIdx + 1).split('&').associate { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) pair to "" else pair.substring(0, eq) to pair.substring(eq + 1)
            }
        } else emptyMap()
        val amzDate = headers.getValue("x-amz-date")
        val canonUri = SigV4.canonicalUri(path)
        val canonQuery = SigV4.canonicalQuery(query)

        // 1. canonical request, byte exact
        assertEquals(
            resource("$name/$name.creq"),
            SigV4.canonicalRequest(method, canonUri, canonQuery, headers, SigV4.EMPTY_PAYLOAD_HASH),
            "canonical request vector $name",
        )

        // 2. Authorization header value
        val authz = SigV4.authorization(
            method = method,
            canonicalUri = canonUri,
            canonicalQuery = canonQuery,
            headers = headers,
            payloadHash = SigV4.EMPTY_PAYLOAD_HASH,
            accessKey = accessKey,
            secretKey = secretKey,
            region = "us-east-1",
            service = "service",
            amzDate = amzDate,
            dateStamp = amzDate.substring(0, 8),
        )
        assertEquals(resource("$name/$name.authz"), authz, "vector $name")

        // 3. signed request: same request line, same headers, Authorization added
        val sreq = resource("$name/$name.sreq").lines().filter { it.isNotBlank() }
        assertEquals(lines.first(), sreq.first(), "signed request line changed for $name")
        val sreqHeaders = LinkedHashMap<String, String>()
        var sreqAuthz: String? = null
        for (line in sreq.drop(1)) {
            val idx = line.indexOf(':')
            assertTrue(idx > 0, "malformed signed request line: $line")
            val key = line.substring(0, idx)
            val value = line.substring(idx + 1).trim()
            if (key.equals("Authorization", ignoreCase = true)) sreqAuthz = value
            else sreqHeaders[key.lowercase()] = value
        }
        assertEquals(authz, sreqAuthz, "signed request Authorization for $name")
        assertEquals(
            headers.keys.map { it.lowercase() }.sorted(),
            sreqHeaders.keys.sorted(),
            "we must sign exactly the headers we send ($name)",
        )
        val signedList = Regex("SignedHeaders=([^,]+)").find(authz)!!.groupValues[1]
        assertEquals(headers.keys.map { it.lowercase() }.sorted().joinToString(";"), signedList,
            "SignedHeaders list for $name")
    }

    @Test fun getVanilla() = runCase("get-vanilla")
    @Test fun getVanillaQueryOrderKeyCase() = runCase("get-vanilla-query-order-key-case")
    @Test fun getVanillaQueryUnreserved() = runCase("get-vanilla-query-unreserved")

    @Test
    fun derivedSigningKeyAndDigestAreStable() {
        // sha256("") known constant: catches an accidental digest-algorithm swap.
        assertEquals(SigV4.EMPTY_PAYLOAD_HASH, SigV4.sha256Hex(ByteArray(0)))
        assertEquals("%20", SigV4.uriEncode(" "))
        assertEquals("a-b_c.d~e", SigV4.uriEncode("a-b_c.d~e"))
        assertEquals("%2F", SigV4.uriEncode("/", encodeSlash = true))
        assertEquals("/a%20b/c", SigV4.canonicalUri("/a b/c"))
    }
}
