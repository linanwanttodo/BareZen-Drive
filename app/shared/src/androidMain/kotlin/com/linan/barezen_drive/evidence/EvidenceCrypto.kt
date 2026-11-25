package com.linan.barezen_drive.evidence

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object EvidenceCrypto {
    const val PBKDF2_ITERATIONS = MANIFEST_ITERATIONS
    const val SALT_BYTES = 32
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16

    private val random = SecureRandom()

    data class DerivedKeys(val kek: ByteArray, val manifestKey: ByteArray)
    data class SealedBytes(val nonce: ByteArray, val ciphertextAndTag: ByteArray)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun derive(password: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): DerivedKeys {
        require(salt.size == SALT_BYTES)
        require(iterations > 0)
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 512)
        val bytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return DerivedKeys(
            bytes.copyOfRange(0, KEY_BYTES),
            bytes.copyOfRange(KEY_BYTES, KEY_BYTES * 2),
        ).also { bytes.fill(0) }
    }

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): SealedBytes {
        require(key.size == KEY_BYTES)
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        aad?.let(cipher::updateAAD)
        return SealedBytes(nonce, cipher.doFinal(plaintext))
    }

    fun open(key: ByteArray, sealed: SealedBytes, aad: ByteArray? = null): ByteArray {
        require(key.size == KEY_BYTES)
        require(sealed.nonce.size == NONCE_BYTES)
        require(sealed.ciphertextAndTag.size >= TAG_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        return try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, sealed.nonce),
            )
            aad?.let(cipher::updateAAD)
            cipher.doFinal(sealed.ciphertextAndTag)
        } catch (e: GeneralSecurityException) {
            throw SecurityException("AES-GCM authentication failed", e)
        }
    }

    fun hmacSha256(key: ByteArray, bytes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(bytes)
    }

    fun isConstantTimeEqual(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
