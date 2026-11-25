package com.linan.barezen_drive.evidence

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsAny
import kotlin.js.JsBoolean
import kotlin.js.JsArray
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.js.Promise
import kotlin.js.js
import kotlin.js.unsafeCast

@OptIn(ExperimentalJsExport::class)
private external interface SubtleCrypto {
    fun importKey(
        format: String,
        keyData: JsAny?,
        algorithm: JsAny,
        extractable: Boolean,
        keyUsages: JsArray<JsString>,
    ): Promise<JsAny>

    fun deriveBits(algorithm: JsAny, baseKey: JsAny, length: Int): Promise<ArrayBuffer>
    fun decrypt(algorithm: JsAny, key: JsAny, data: ArrayBuffer): Promise<ArrayBuffer>
    fun verify(algorithm: JsAny, key: JsAny, signature: ArrayBuffer, data: ArrayBuffer): Promise<JsBoolean>
}

data class WebVaultKeys(val dek: CryptoKey, val manifestKey: CryptoKey)

@OptIn(ExperimentalJsExport::class)
external class CryptoKey : JsAny

private val subtle: SubtleCrypto = js("crypto.subtle")

private fun usages(vararg values: String): JsArray<JsString> =
    values.map { it.toJsString() }.toJsArray()

private external interface Pbkdf2Params : JsAny {
    var name: String
    var salt: ArrayBuffer
    var iterations: Int
    var hash: String
}

private external interface AesGcmParams : JsAny {
    var name: String
    var iv: ArrayBuffer
    var additionalData: ArrayBuffer?
    var tagLength: Int
}

private fun newObject(): JsAny = js("({})")
private fun encodePassword(password: String): Int8Array = js("new TextEncoder().encode(password)")
private fun sliceBuffer(buffer: ArrayBuffer, start: Int, end: Int): ArrayBuffer = js("buffer.slice(start, end)")

private fun algorithm(name: String): JsAny =
    newObject().unsafeCast<JsAlgorithm>().also { it.name = name }

private external interface JsAlgorithm : JsAny {
    var name: String
    var hash: String
}

private fun hmacAlgorithm(): JsAny =
    newObject().unsafeCast<JsAlgorithm>().also {
        it.name = "HMAC"
        it.hash = "SHA-256"
    }

suspend fun deriveWebVaultKeys(password: String, salt: ByteArray, iterations: Int): WebVaultKeys {
    val encoded = encodePassword(password)
    val base = subtle.importKey("raw", encoded.buffer, algorithm("PBKDF2"), false, usages("deriveBits")).await()
    val params = newObject().unsafeCast<Pbkdf2Params>()
    params.name = "PBKDF2"
    params.salt = salt.toArrayBuffer()
    params.iterations = iterations
    params.hash = "SHA-256"
    val bits = subtle.deriveBits(params, base, 512).await()
    val kekBytes = sliceBuffer(bits, 0, 32).toByteArray()
    val manifestBytes = sliceBuffer(bits, 32, 64).toByteArray()
    val kek = importAesKey(kekBytes, usages("decrypt"))
    val manifestKey = importHmacKey(manifestBytes, usages("verify"))
    kekBytes.fill(0)
    manifestBytes.fill(0)
    return WebVaultKeys(kek, manifestKey)
}

internal suspend fun importAesKey(bytes: ByteArray, keyUsages: JsArray<JsString>): CryptoKey =
    subtle.importKey("raw", bytes.toArrayBuffer(), algorithm("AES-GCM"), false, keyUsages).await().unsafeCast<CryptoKey>()

private suspend fun importHmacKey(bytes: ByteArray, keyUsages: JsArray<JsString>): CryptoKey =
    subtle.importKey("raw", bytes.toArrayBuffer(), hmacAlgorithm(), false, keyUsages).await().unsafeCast<CryptoKey>()

suspend fun webUnlockDek(keys: WebVaultKeys, wrappedDek: ByteArray, verifier: ByteArray): ByteArray {
    val dek = webDecrypt(keys.dek, wrappedDek.copyOfRange(0, 12), wrappedDek.copyOfRange(12, wrappedDek.size), null)
    try {
        val dekKey = importAesKey(dek, usages("decrypt"))
        val check = webDecrypt(dekKey, verifier.copyOfRange(0, 12), verifier.copyOfRange(12, verifier.size), null)
        check(check.size == 32) { "password verifier failed" }
        check.fill(0)
        return dek
    } catch (failure: Throwable) {
        dek.fill(0)
        throw failure
    }
}

suspend fun webDecrypt(key: CryptoKey, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ByteArray =
    webDecryptBuffer(key, nonce, ciphertext, aad).toByteArray()

internal suspend fun webDecryptBuffer(key: CryptoKey, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray?): ArrayBuffer {
    val params = newObject().unsafeCast<AesGcmParams>()
    params.name = "AES-GCM"
    params.iv = nonce.toArrayBuffer()
    if (aad != null) params.additionalData = aad.toArrayBuffer()
    params.tagLength = 128
    return subtle.decrypt(params, key, ciphertext.toArrayBuffer()).await()
}

suspend fun webVerifyManifestTag(key: CryptoKey, bytes: ByteArray, tag: ByteArray): Boolean =
    subtle.verify(algorithm("HMAC"), key, tag.toArrayBuffer(), bytes.toArrayBuffer()).await().toBoolean()

fun ByteArray.toArrayBuffer(): ArrayBuffer {
    val view = Int8Array(size)
    for (i in indices) view[i] = this[i]
    return view.buffer
}

fun ArrayBuffer.toByteArray(): ByteArray {
    val view = Int8Array(this)
    return ByteArray(view.length) { view[it] }
}
