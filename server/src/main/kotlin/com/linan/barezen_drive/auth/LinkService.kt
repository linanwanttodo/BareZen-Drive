package com.linan.barezen_drive.auth

import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless short-lived capability URLs. A signature binds one file id to one
 * expiry and is verified with the same secret as JWT signing, so the signed
 * content/thumbnail endpoints can be consumed by agents that cannot send an
 * Authorization header (browser tabs, media players). This is also the
 * foundation for the future share-link feature.
 */
object LinkService {
    private lateinit var secretBytes: ByteArray

    fun init(secret: String) {
        if (::secretBytes.isInitialized) return
        secretBytes = secret.encodeToByteArray()
    }

    fun sign(fileId: UUID, expEpochSeconds: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        return mac.doFinal(payload(fileId, expEpochSeconds).encodeToByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun verify(fileId: UUID, expEpochSeconds: Long, sig: String): Boolean {
        if (expEpochSeconds <= System.currentTimeMillis() / 1000) return false
        // Constant-time comparison so response timing cannot leak the signature.
        return MessageDigest.isEqual(
            sign(fileId, expEpochSeconds).encodeToByteArray(),
            sig.lowercase().encodeToByteArray(),
        )
    }

    private fun payload(fileId: UUID, exp: Long) = "content:$fileId:$exp"
}
