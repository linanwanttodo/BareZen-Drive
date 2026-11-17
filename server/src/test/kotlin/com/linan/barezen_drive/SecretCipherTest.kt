package com.linan.barezen_drive

import com.linan.barezen_drive.auth.SecretCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HKDF 的 RFC 5869 官方向量 + AES-GCM 的封装性质。
 *
 * 向量是逐字节钉死的：手写 extract+expand 最容易错的是 salt 为空时要用
 * HashLen 个零字节当 HMAC 密钥，以及 T(i) 的计数器是单字节而不是四字节。
 */
class SecretCipherTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun toHex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val okm = SecretCipher.hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            toHex(okm),
        )
    }

    @Test
    fun hkdfMatchesRfc5869Case3WithEmptySaltAndInfo() {
        val okm = SecretCipher.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            toHex(okm),
        )
    }

    @Test
    fun sealThenOpenReturnsThePlaintext() {
        SecretCipher.init("test-secret-0123456789abcdef0123456789abcdef")
        val sealed = SecretCipher.seal("a".repeat(64), "token-id-1")
        assertEquals("a".repeat(64), SecretCipher.open(sealed, "token-id-1"))
    }

    @Test
    fun twoSealsOfTheSamePlaintextDiffer() {
        SecretCipher.init("test-secret-0123456789abcdef0123456789abcdef")
        val a = SecretCipher.seal("secret", "id")
        val b = SecretCipher.seal("secret", "id")
        assertTrue(a != b, "nonce 必须每次新生成")
    }

    @Test
    fun wrongAadDoesNotOpen() {
        SecretCipher.init("test-secret-0123456789abcdef0123456789abcdef")
        val sealed = SecretCipher.seal("secret", "id-1")
        assertNull(SecretCipher.open(sealed, "id-2"))
    }

    @Test
    fun tamperedCiphertextDoesNotOpen() {
        SecretCipher.init("test-secret-0123456789abcdef0123456789abcdef")
        val sealed = SecretCipher.seal("secret", "id")
        val raw = java.util.Base64.getDecoder().decode(sealed)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        assertNull(SecretCipher.open(java.util.Base64.getEncoder().encodeToString(raw), "id"))
    }

    @Test
    fun garbageDoesNotThrow() {
        SecretCipher.init("test-secret-0123456789abcdef0123456789abcdef")
        assertNull(SecretCipher.open("not-base64!!", "id"))
        assertNull(SecretCipher.open("", "id"))
        assertNull(SecretCipher.open(java.util.Base64.getEncoder().encodeToString(ByteArray(4)), "id"))
    }

    @Test
    fun sealedFormFitsTheColumn() {
        SecretCipher.init("test-secret-0123456789abcdef0123456789abcdef")
        // 12 nonce + 64 明文 + 16 tag = 92 字节 → base64 124 字符，列宽 255 足够。
        assertTrue(SecretCipher.seal("f".repeat(64), "id").length <= 255)
    }
}