package com.linan.barezen_drive.evidence

import com.linan.barezen_drive.data.evidence.VAULT_ID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class VaultManifest(
    val version: Int,
    val vaultId: String,
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val wrappedDek: String,
    val passwordVerifier: String,
    val dekVersion: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val tag: String? = null,
) {
    fun unsignedBytes(): ByteArray = EvidenceJson.encodeToString(copy(tag = null)).encodeToByteArray()

    fun signedBytes(): ByteArray = EvidenceJson.encodeToString(this).encodeToByteArray()

    fun tagBytes(): ByteArray = requireNotNull(tag).hexToBytes()
}

val EvidenceJson = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = false
}

const val MANIFEST_KDF = "PBKDF2WithHmacSHA256"
const val MANIFEST_ITERATIONS = 600_000
const val EVIDENCE_FORMAT_VERSION = 1
const val DEK_VERSION = 1

fun parseVaultManifest(bytes: ByteArray): VaultManifest {
    require(bytes.size <= 1_048_576) { "manifest too large" }
    val text = bytes.decodeToString(throwOnInvalidSequence = true)
    val manifest = EvidenceJson.decodeFromString<VaultManifest>(text)
    validateVaultManifestShape(manifest)
    require(bytes.contentEquals(manifest.signedBytes())) { "manifest is not canonical" }
    return manifest
}

fun validateVaultManifestShape(manifest: VaultManifest) {
    require(manifest.version == EVIDENCE_FORMAT_VERSION)
    require(manifest.kdf == MANIFEST_KDF)
    require(manifest.iterations == MANIFEST_ITERATIONS)
    require(manifest.vaultId.matches(VAULT_ID))
    require(manifest.dekVersion == DEK_VERSION)
    require(manifest.createdAtMs >= 0 && manifest.updatedAtMs >= manifest.createdAtMs)
    require(Base64.decode(manifest.salt).size == 32)
    require(Base64.decode(manifest.wrappedDek).size == 60)
    require(Base64.decode(manifest.passwordVerifier).size == 60)
    require(manifest.tag != null && manifest.tag.matches(Regex("[0-9a-f]{64}")))
}

@OptIn(ExperimentalEncodingApi::class)
fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

@OptIn(ExperimentalEncodingApi::class)
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0 && matches(Regex("[0-9a-fA-F]+")))
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
