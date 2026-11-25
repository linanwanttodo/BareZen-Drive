package com.linan.barezen_drive.evidence

import com.linan.barezen_drive.data.evidence.VAULT_ID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class UnlockedManifest(val dek: ByteArray, val manifest: VaultManifest)

@OptIn(ExperimentalEncodingApi::class)
fun createVaultManifest(
    vaultId: String,
    password: String,
    dek: ByteArray,
    nowMs: Long,
    createdAtMs: Long = nowMs,
): VaultManifest {
    EvidencePasswordPolicy.validate(password)
    require(vaultId.matches(VAULT_ID))
    require(dek.size == EvidenceCrypto.KEY_BYTES)
    val salt = EvidenceCrypto.randomBytes(EvidenceCrypto.SALT_BYTES)
    val keys = EvidenceCrypto.derive(EvidencePasswordPolicy.normalize(password), salt)
    return try {
        val verifier = EvidenceCrypto.randomBytes(32)
        val unsigned = VaultManifest(
            version = EVIDENCE_FORMAT_VERSION,
            vaultId = vaultId,
            kdf = MANIFEST_KDF,
            iterations = MANIFEST_ITERATIONS,
            salt = Base64.encode(salt),
            wrappedDek = Base64.encode(
                EvidenceCrypto.seal(keys.kek, dek).let { it.nonce + it.ciphertextAndTag },
            ),
            passwordVerifier = Base64.encode(
                EvidenceCrypto.seal(dek, verifier).let { it.nonce + it.ciphertextAndTag },
            ),
            dekVersion = DEK_VERSION,
            createdAtMs = createdAtMs,
            updatedAtMs = nowMs,
        )
        unsigned.copy(
            tag = EvidenceCrypto.hmacSha256(keys.manifestKey, unsigned.unsignedBytes()).toHex(),
        )
    } finally {
        keys.kek.fill(0)
        keys.manifestKey.fill(0)
    }
}

@OptIn(ExperimentalEncodingApi::class)
fun unlockVaultManifest(manifest: VaultManifest, password: String): UnlockedManifest {
    EvidencePasswordPolicy.validate(password)
    validateVaultManifestShape(manifest)
    val keys = EvidenceCrypto.derive(
        EvidencePasswordPolicy.normalize(password),
        Base64.decode(manifest.salt),
        manifest.iterations,
    )
    return try {
        val expected = EvidenceCrypto.hmacSha256(keys.manifestKey, manifest.unsignedBytes())
        if (!EvidenceCrypto.isConstantTimeEqual(expected, manifest.tagBytes())) {
            throw SecurityException("manifest authentication failed")
        }
        val dek = EvidenceCrypto.open(keys.kek, manifest.wrappedDek.decodeSealed())
        val verifier = EvidenceCrypto.open(dek, manifest.passwordVerifier.decodeSealed())
        if (verifier.size != 32) throw SecurityException("password verifier failed")
        verifier.fill(0)
        UnlockedManifest(dek, manifest)
    } finally {
        keys.kek.fill(0)
        keys.manifestKey.fill(0)
    }
}

@OptIn(ExperimentalEncodingApi::class)
fun String.decodeSealed(): EvidenceCrypto.SealedBytes {
    val bytes = Base64.decode(this)
    require(bytes.size == 60) { "invalid sealed value" }
    return EvidenceCrypto.SealedBytes(
        bytes.copyOfRange(0, EvidenceCrypto.NONCE_BYTES),
        bytes.copyOfRange(EvidenceCrypto.NONCE_BYTES, bytes.size),
    )
}
