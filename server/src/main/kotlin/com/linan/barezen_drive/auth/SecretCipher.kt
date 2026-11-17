package com.linan.barezen_drive.auth

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SigV4 校验必须拿到 secret 本身做 HMAC，而 token_hash 只存 SHA-256。所以令牌行
 * 额外保存一份可解密副本，密钥由 JWT_SECRET 派生。
 *
 * 由 JWT_SECRET 派生不扩大暴露面：它泄露本来就等于全站失守（可自签任意用户
 * JWT）。反过来只泄露数据库时，拿到的是没有密钥就解不开的 AES-GCM 密文。
 * AAD 绑定令牌 id，把密文复制到另一行解不开。
 */
object SecretCipher {
    private const val INFO = "barezen:s3-token:v1"
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()
    private lateinit var key: ByteArray

    /** 与 JwtService.init 同样幂等：重启或新测试实例复用已派生的密钥。 */
    fun init(secret: String) {
        if (::key.isInitialized) return
        key = hkdfSha256(secret.toByteArray(Charsets.UTF_8), ByteArray(0), INFO.toByteArray(Charsets.UTF_8), 32)
    }

    fun seal(plaintext: String, aad: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(nonce + body)
    }

    /** 任何失败都返回 null：换过 JWT_SECRET、密文被改、AAD 不符，对调用方是同一件事。 */
    fun open(sealed: String, aad: String): String? = runCatching {
        val raw = Base64.getDecoder().decode(sealed)
        require(raw.size > NONCE_BYTES + TAG_BITS / 8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, NONCE_BYTES)),
        )
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(raw.copyOfRange(NONCE_BYTES, raw.size)), Charsets.UTF_8)
    }.getOrNull()

    /**
     * RFC 5869 的 extract + expand。JDK 21 没有 javax.crypto.KDF（Java 24 起才有），
     * 所以手写；正确性由 RFC 官方向量钉住，而不是由"看起来对"钉住。
     */
    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "HKDF length out of range" }
        // RFC 5869 §2.2：salt 未提供时用 HashLen 个零字节。HMAC 会把短密钥补零到
        // 块长，所以这与"空 salt"在 HMAC 下等价，但显式写出来才与向量对得上。
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            val take = minOf(block.size, length - offset)
            block.copyInto(out, offset, 0, take)
            offset += take
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}