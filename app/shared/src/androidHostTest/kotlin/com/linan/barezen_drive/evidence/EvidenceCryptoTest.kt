package com.linan.barezen_drive.evidence

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EvidenceCryptoTest {
    @Test
    fun pbkdf2MatchesKnownAnswerAndSplitsKeys() {
        val derived = EvidenceCrypto.derive(
            "correct horse battery staple",
            ByteArray(32) { it.toByte() },
            iterations = 1_000,
        )
        assertContentEquals(
            "22c37d144ef39fba5ac507f839d901d04719c150d9ea44177a60adf6c8b073f8".hexToBytes(),
            derived.kek,
        )
        assertContentEquals(
            "1abbb6e717f6bd054ee7f4bb53938ee7e9a8cedf1818a7983682955df6d3f7de".hexToBytes(),
            derived.manifestKey,
        )
    }

    @Test
    fun sealedBytesRejectWrongKeyAndTampering() {
        val key = ByteArray(32) { 7 }
        val plain = "evidence".encodeToByteArray()
        val sealed = EvidenceCrypto.seal(key, plain)
        assertContentEquals(plain, EvidenceCrypto.open(key, sealed))
        assertFailsWith<SecurityException> {
            EvidenceCrypto.open(key.copyOf().also { it[0] = 8 }, sealed)
        }
        assertFailsWith<SecurityException> {
            EvidenceCrypto.open(
                key,
                sealed.copy(ciphertextAndTag = sealed.ciphertextAndTag.copyOf().also {
                    it[0] = (it[0].toInt() xor 1).toByte()
                }),
            )
        }
        assertTrue(EvidenceCrypto.isConstantTimeEqual(ByteArray(16), ByteArray(16)))
    }

    @Test
    fun manifestUsesCanonicalBytesAndSeparateAuthenticationKey() {
        val manifest = createVaultManifest(
            "a".repeat(32),
            "twelve-chars!",
            ByteArray(32) { 3 },
            1_700_000_000_000,
        )
        val keys = EvidenceCrypto.derive("twelve-chars!", Base64.decode(manifest.salt))
        assertContentEquals(
            EvidenceCrypto.hmacSha256(keys.manifestKey, manifest.unsignedBytes()),
            manifest.tagBytes(),
        )
        assertEquals(60, Base64.decode(manifest.wrappedDek).size)
        assertEquals(60, Base64.decode(manifest.passwordVerifier).size)
        assertContentEquals(ByteArray(32) { 3 }, unlockVaultManifest(manifest, "twelve-chars!").dek)
        assertFailsWith<SecurityException> { unlockVaultManifest(manifest, "wrong password") }
        assertFailsWith<SecurityException> {
            unlockVaultManifest(manifest.copy(updatedAtMs = manifest.updatedAtMs + 1), "twelve-chars!")
        }
    }

    @Test
    fun passwordLengthCountsCodePointsAndDoesNotTrim() {
        assertTrue(EvidencePasswordPolicy.isValid("123456789012"))
        assertTrue(EvidencePasswordPolicy.isValid(" 123456789012 "))
        assertTrue(EvidencePasswordPolicy.isValid("😀".repeat(12)))
        assertFailsWith<IllegalArgumentException> { EvidencePasswordPolicy.validate("12345678901") }
        assertFailsWith<IllegalArgumentException> { EvidencePasswordPolicy.validate("a\u0301".repeat(6)) }
        assertFailsWith<IllegalArgumentException> { EvidencePasswordPolicy.validate("a".repeat(129)) }
    }

    @Test
    fun fifthFailureStartsCooldownAndSuccessResets() {
        var now = 1_000L
        val policy = AttemptPolicy(nowMs = { now })
        repeat(4) { policy.recordFailure() }
        assertEquals(0L, policy.remainingCooldownMs())
        policy.recordFailure()
        assertEquals(60_000L, policy.remainingCooldownMs())
        assertFailsWith<IllegalStateException> { policy.assertAllowed() }
        now += 60_000
        policy.assertAllowed()
        policy.recordSuccess()
        assertEquals(0L, policy.remainingCooldownMs())
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
