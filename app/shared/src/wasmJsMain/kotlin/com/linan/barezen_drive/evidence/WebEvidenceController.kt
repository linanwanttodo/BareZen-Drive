package com.linan.barezen_drive.evidence

import com.linan.barezen_drive.data.api.AppClient
import com.linan.barezen_drive.data.evidence.EvidenceCloudVault
import com.linan.barezen_drive.data.evidence.EvidenceController
import com.linan.barezen_drive.data.evidence.EvidenceErrorKind
import com.linan.barezen_drive.data.evidence.EvidenceException
import com.linan.barezen_drive.data.evidence.EvidenceLens
import com.linan.barezen_drive.data.evidence.EvidenceMedia
import com.linan.barezen_drive.data.evidence.EvidenceMode
import com.linan.barezen_drive.data.evidence.EvidencePlaybackRequest
import com.linan.barezen_drive.data.evidence.EvidenceRecordingState
import com.linan.barezen_drive.data.evidence.EvidenceSegmentState
import com.linan.barezen_drive.data.evidence.EvidenceSegmentView
import com.linan.barezen_drive.data.evidence.EvidenceSessionView
import com.linan.barezen_drive.data.evidence.EvidenceStorageSummary
import com.linan.barezen_drive.data.evidence.EvidenceVaultStatus
import com.linan.barezen_drive.data.evidence.SESSION_ID
import com.linan.barezen_drive.data.evidence.SegmentKey
import com.linan.barezen_drive.data.evidence.VAULT_ID
import com.linan.barezen_drive.platform.FileSaveRequest
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Instant
import kotlinx.io.readByteArray
import kotlinx.browser.window
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.fetch.Response
import kotlin.io.encoding.Base64
import kotlin.js.js
import kotlin.js.toJsArray
import kotlin.js.unsafeCast

class WebEvidenceController : EvidenceController {
    override val recording = MutableStateFlow(EvidenceRecordingState())
    override val storage = MutableStateFlow<List<EvidenceStorageSummary>>(emptyList())
    override val canRecord = false
    private val files get() = AppClient.files
    private var vaultId: String? = null
    private var dek: ByteArray? = null
    private var segments = emptyList<EvidenceSegmentView>()
    private val objectUrls = mutableMapOf<String, String>()
    private val blobSizes = mutableMapOf<String, Long>()
    private var lockVersion = 0L

    override suspend fun status(): EvidenceVaultStatus {
        if (vaultId != null && dek != null) return EvidenceVaultStatus.UNLOCKED
        cloudVaults()
        return EvidenceVaultStatus.LOCKED
    }

    override suspend fun createVault(password: String): Unit = readOnly()

    override suspend fun unlock(password: String) {
        val cloud = cloudVaults().firstOrNull() ?: throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
        restore(cloud.vaultId, password)
    }

    override suspend fun cooldownSeconds(): Long = 0

    override fun lock() {
        lockVersion += 1
        dek?.fill(0)
        dek = null
        vaultId = null
        segments = emptyList()
        storage.value = emptyList()
        objectUrls.values.forEach(URL::revokeObjectURL)
        objectUrls.clear()
        blobSizes.clear()
    }

    override suspend fun start(mode: EvidenceMode, lens: EvidenceLens): Unit = readOnly()

    override suspend fun stop() = Unit

    override suspend fun sessions(): List<EvidenceSessionView> {
        requireUnlocked()
        return segments.groupBy { it.key.sessionId }.map { (sessionId, rows) ->
            val first = rows.first()
            EvidenceSessionView(
                sessionId = sessionId,
                mode = if (first.media == EvidenceMedia.VIDEO) EvidenceMode.VIDEO else EvidenceMode.AUDIO,
                startedAtMs = first.startedAtMs,
                segmentCount = rows.size,
                uploadedCount = rows.size,
                failedCount = 0,
                localBytes = 0,
                endedAtMs = rows.maxOf { it.startedAtMs + it.durationMs },
                state = "UPLOADED",
                segments = rows.sortedBy { it.key.segmentNumber },
            )
        }.sortedByDescending { it.startedAtMs }
    }

    override suspend fun cloudVaults(): List<EvidenceCloudVault> {
        val root = files.contents("root", includeEvidence = true).getOrThrow().folders.firstOrNull { it.name == ROOT_NAME }
            ?: return emptyList()
        return files.contents(root.id, includeEvidence = true).getOrThrow().folders.filter { it.name.matches(VAULT_ID) }.mapNotNull { folder ->
            val manifest = files.contents(folder.id, includeEvidence = true).getOrThrow().files.firstOrNull { it.name == "vault.json" }
                ?: return@mapNotNull null
            EvidenceCloudVault(folder.name, manifest.id, parseTime(manifest.updatedAt))
        }
    }

    override suspend fun restore(vaultId: String, password: String) {
        val normalized = normalizeEvidencePassword(password)
        if (!evidencePasswordLength(normalized)) throw EvidenceException(EvidenceErrorKind.PASSWORD_LENGTH)
        val cloud = cloudVaults().firstOrNull { it.vaultId == vaultId }
            ?: throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
        val bytes = try {
            files.download(cloud.manifestFileId).readRemaining().readByteArray()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw EvidenceException(EvidenceErrorKind.NETWORK_UNAVAILABLE)
        }
        val manifest = try { parseVaultManifest(bytes) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw EvidenceException(EvidenceErrorKind.MANIFEST_INVALID)
        }
        if (manifest.vaultId != cloud.vaultId) throw EvidenceException(EvidenceErrorKind.MANIFEST_INVALID)
        val keys = try {
            deriveWebVaultKeys(normalized, Base64.decode(manifest.salt), manifest.iterations)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw EvidenceException(EvidenceErrorKind.PASSWORD_WRONG)
        }
        val authenticated = try {
            webVerifyManifestTag(keys.manifestKey, manifest.unsignedBytes(), manifest.tagBytes())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw EvidenceException(EvidenceErrorKind.PASSWORD_WRONG)
        }
        if (!authenticated) throw EvidenceException(EvidenceErrorKind.PASSWORD_WRONG)
        val unlocked = try {
            webUnlockDek(keys, Base64.decode(manifest.wrappedDek), Base64.decode(manifest.passwordVerifier))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw EvidenceException(EvidenceErrorKind.MANIFEST_INVALID)
        }
        val restored = try { cloudSegments(vaultId) } catch (cancelled: CancellationException) {
            unlocked.fill(0)
            throw cancelled
        } catch (_: Throwable) {
            unlocked.fill(0)
            throw EvidenceException(EvidenceErrorKind.NETWORK_UNAVAILABLE)
        }
        lock()
        this.vaultId = vaultId
        dek = unlocked
        segments = restored
        storage.value = restored.groupBy { it.key.sessionId }.map { (sessionId, rows) ->
            EvidenceStorageSummary(sessionId, rows.size, 0)
        }
    }

    override suspend fun changePassword(oldPassword: String, newPassword: String): Unit = readOnly()

    override suspend fun deleteVault(): Unit = readOnly()

    override suspend fun playback(segmentKey: String): EvidencePlaybackRequest {
        val version = lockVersion
        val segment = requireSegment(segmentKey)
        val cipher = try {
            files.download(requireNotNull(segment.cloudFileId)).readRemaining().readByteArray()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw EvidenceException(EvidenceErrorKind.NETWORK_UNAVAILABLE)
        }
        if (lockVersion != version) throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
        val blob = try { decryptWebSegment(cipher, requireNotNull(dek), segment.key, segment.media) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw EvidenceException(EvidenceErrorKind.DECRYPT_FAILED)
        }
        if (lockVersion != version) throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
        val token = randomWebBytes(16).toHex()
        val url = URL.createObjectURL(blob)
        objectUrls[token] = url
        blobSizes[token] = blob.size.toDouble().toLong()
        return EvidencePlaybackRequest(token, url, segment.media)
    }

    override fun closePlayback(token: String) {
        objectUrls.remove(token)?.let(URL::revokeObjectURL)
        blobSizes.remove(token)
    }

    override suspend fun exportRequest(segmentKey: String): FileSaveRequest? {
        val request = playback(segmentKey)
        val segment = requireSegment(segmentKey)
        return FileSaveRequest(
            id = request.token,
            name = "evidence-${segment.key.sessionId}-${segment.key.segmentNumber.toString().padStart(3, '0')}.${if (segment.media == EvidenceMedia.VIDEO) "mp4" else "m4a"}",
            mime = if (segment.media == EvidenceMedia.VIDEO) "video/mp4" else "audio/mp4",
            size = blobSizes[request.token] ?: 0,
            open = {
                val response = window.fetch(request.path).await() as Response
                ByteReadChannel(response.arrayBuffer().await().toByteArray())
            },
        )
    }

    override suspend fun deleteLocalSegments(segmentKeys: List<String>) = Unit

    private suspend fun cloudSegments(vaultId: String): List<EvidenceSegmentView> {
        val vault = files.contents("root", includeEvidence = true).getOrThrow().folders.firstOrNull { it.name == ROOT_NAME }
            ?.let { files.contents(it.id, includeEvidence = true).getOrThrow().folders.firstOrNull { folder -> folder.name == vaultId } }
            ?: throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
        return files.contents(vault.id, includeEvidence = true).getOrThrow().folders.filter { SESSION_ID.matches(it.name) }.flatMap { folder ->
            files.contents(folder.id, includeEvidence = true).getOrThrow().files.mapNotNull { file ->
                val match = SEGMENT_NAME.matchEntire(file.name) ?: return@mapNotNull null
                val number = match.groupValues[1].toInt()
                if (number !in 1..999) return@mapNotNull null
                val media = if (match.groupValues[2] == "video.mp4") EvidenceMedia.VIDEO else EvidenceMedia.AUDIO
                EvidenceSegmentView(
                    key = SegmentKey(vaultId, folder.name, number),
                    media = media,
                    state = EvidenceSegmentState.UPLOADED,
                    startedAtMs = parseTime(file.createdAt) ?: 0,
                    durationMs = 0,
                    cipherBytes = file.size,
                    localFileId = null,
                    cloudFileId = file.id,
                    error = null,
                )
            }
        }
    }

    private fun requireUnlocked() {
        if (vaultId == null || dek == null) throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
    }

    private fun requireSegment(value: String): EvidenceSegmentView {
        requireUnlocked()
        val key = SegmentKey.parse(value)
        if (key.vaultId != vaultId) throw EvidenceException(EvidenceErrorKind.DECRYPT_FAILED)
        return segments.firstOrNull { it.key == key } ?: throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
    }

    private fun readOnly(): Nothing = throw EvidenceException(EvidenceErrorKind.UNKNOWN)

    private companion object {
        const val ROOT_NAME = "取证"
        val SEGMENT_NAME = Regex("([0-9]{3})_(video\\.mp4|audio\\.m4a)\\.enc")
    }
}

internal suspend fun decryptWebSegment(
    cipher: ByteArray,
    dek: ByteArray,
    expected: SegmentKey,
    expectedMedia: EvidenceMedia,
): Blob {
    check(cipher.size >= SEGMENT_FIXED_HEADER_BYTES)
    val sessionLength = ((cipher[82].toInt() and 0xff) shl 8) or (cipher[83].toInt() and 0xff)
    val headerSize = SEGMENT_FIXED_HEADER_BYTES + sessionLength
    check(headerSize <= cipher.size)
    val headerBytes = cipher.copyOfRange(0, headerSize)
    val header = decodeSegmentHeader(headerBytes)
    check(header.vaultId == expected.vaultId && header.sessionId == expected.sessionId)
    check(header.segmentNumber == expected.segmentNumber && header.media == expectedMedia)
    check(cipher.size.toLong() == segmentCiphertextLength(header)) { "invalid ciphertext length" }
    val key = importAesKey(dek, usagesForDecrypt())
    val parts = ArrayList<JsAny?>()
    var offset = headerSize
    repeat(header.chunkCount.toInt()) { index ->
        check(offset + 28 <= cipher.size)
        val nonce = cipher.copyOfRange(offset, offset + 12)
        val encryptedLength = minOf(SEGMENT_CHUNK_BYTES.toLong(), header.plaintextLength - index.toLong() * SEGMENT_CHUNK_BYTES).toInt() + 16
        check(offset + 12 + encryptedLength <= cipher.size)
        val encrypted = cipher.copyOfRange(offset + 12, offset + 12 + encryptedLength)
        parts.add(webDecryptBuffer(key, nonce, encrypted, segmentAad(headerBytes, index.toLong())))
        offset += 12 + encryptedLength
    }
    check(offset == cipher.size)
    return Blob(parts.toTypedArray().toJsArray().unsafeCast<kotlin.js.JsArray<kotlin.js.JsAny?>>(), BlobPropertyBag(type = if (header.media == EvidenceMedia.VIDEO) "video/mp4" else "audio/mp4"))
}

private fun usagesForDecrypt(): kotlin.js.JsArray<kotlin.js.JsString> =
    listOf("decrypt").map { it.toJsString() }.toJsArray()

private fun normalizeEvidencePassword(password: String): String = js("password.normalize('NFC')")
private fun randomView(size: Int): Int8Array = js("crypto.getRandomValues(new Uint8Array(size))")

internal fun randomWebBytes(size: Int): ByteArray {
    val view = randomView(size)
    return ByteArray(view.length) { view[it] }
}

private fun evidencePasswordLength(password: String): Boolean {
    var count = 0
    var index = 0
    while (index < password.length) {
        val code = password[index].code
        index += if (code in 0xd800..0xdbff && index < password.length && password[index].code in 0xdc00..0xdfff) 2 else 1
        count += 1
    }
    return count in 12..128
}

private fun parseTime(value: String): Long? = runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
