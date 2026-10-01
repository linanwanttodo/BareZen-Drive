package com.linan.barezen_drive.auth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The one place opaque bearer tokens are hashed for storage.
 *
 * Refresh tokens, share links and WebDAV app passwords all store a SHA-256 of
 * the secret they handed out. Having each of them carry its own copy looked
 * harmless and was not: the failure mode of changing the hash in one place and
 * not the others is every already-issued token in the untouched systems
 * silently ceasing to authenticate, with no compile error and no test failure
 * pointing at it. One definition makes that a single edit.
 *
 * This is a hash and *not* a password KDF, on purpose. It is only sound for
 * values that are themselves 32 bytes of [SecureRandom], because that has no
 * offline guessing surface - which is exactly what bcrypt would be buying. A
 * human-chosen secret must never be stored through here; those go through
 * [PasswordHasher].
 */
object TokenSecret {
    /** Lowercase hex SHA-256, which is what every token_hash column stores. */
    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray()).toHex()

    /**
     * A fresh token as 64 hex characters, for share links and WebDAV app
     * passwords.
     *
     * Refresh tokens deliberately do NOT use this: they are Base64URL-encoded,
     * and switching them over would invalidate every one already issued, logging
     * every signed-in client out the moment the server upgraded. The encoding is
     * part of the credential, not a detail.
     */
    fun mint(): String = randomBytes().toHex()

    /** Base64URL without padding, the shape refresh tokens have always had. */
    fun mintBase64Url(): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes())

    private fun randomBytes(): ByteArray = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }

    private const val TOKEN_BYTES = 32
    private val random = SecureRandom()

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
