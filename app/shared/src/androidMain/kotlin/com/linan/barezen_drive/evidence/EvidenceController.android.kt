package com.linan.barezen_drive.evidence

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.linan.barezen_drive.AndroidContext
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
import com.linan.barezen_drive.data.evidence.SegmentKey
import com.linan.barezen_drive.data.evidence.SESSION_ID
import com.linan.barezen_drive.data.local.TokenStorage
import com.linan.barezen_drive.evidence.service.EvidenceKeyBoxHolder
import com.linan.barezen_drive.evidence.service.EvidenceRecordingService
import com.linan.barezen_drive.platform.FileSaveRequest
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class AndroidEvidenceController(private val context: Context = AndroidContext.app) : EvidenceController {
    override val recording: StateFlow<EvidenceRecordingState> = EvidenceRecordingService.currentState
    override val storage = MutableStateFlow<List<EvidenceStorageSummary>>(emptyList())
    override val canRecord = true
    private val passwordMutex = Mutex()
    private var unlockedVault: VaultRow? = null
    private var dek: ByteArray? = null
    private var lockRequested = false
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        controllerScope.launch {
            recording.collect { state ->
                if (!state.active && lockRequested) {
                    lockRequested = false
                    clearUnlocked()
                }
            }
        }
    }

    private suspend fun identity(): Pair<String, String> {
        val server = EvidenceIdentity.serverKey()
        val cached = EvidenceIdentity.cached()
        val online = runCatching { AppClient.files.me().getOrThrow().id }.getOrNull()
        val account = online ?: cached?.takeIf { it.first == server }?.second
            ?: throw EvidenceException(EvidenceErrorKind.NETWORK_UNAVAILABLE)
        EvidenceIdentity.save(server, account)
        return server to account
    }

    private suspend fun localVault(): VaultRow? {
        val (server, account) = identity()
        return EvidenceDb.currentVaults(server, account).firstOrNull()
    }

    override suspend fun status(): EvidenceVaultStatus {
        val vault = localVault()
        return if (vault == null) EvidenceVaultStatus.MISSING else if (dek != null && unlockedVault?.vaultId == vault.vaultId) EvidenceVaultStatus.UNLOCKED else EvidenceVaultStatus.LOCKED
    }

    override suspend fun createVault(password: String) = passwordMutex.withLock {
        if (!EvidencePasswordPolicy.isValid(password)) throw EvidenceException(EvidenceErrorKind.PASSWORD_LENGTH)
        val (server, account) = identity()
        val now = System.currentTimeMillis()
        val id = EvidenceNames.vaultId()
        val key = EvidenceCrypto.randomBytes(EvidenceCrypto.KEY_BYTES)
        try {
            val manifest = createVaultManifest(id, password, key, now)
            val bytes = manifest.signedBytes()
            try {
                EvidenceManifestSync.mutex.withLock {
                    val uploaded = EvidenceCloudStore.uploadManifest(id, bytes)
                    val root = EvidenceCloudStore.ensureRoot()
                    val folder = EvidenceCloudStore.ensureVault(id)
                    EvidenceDb.insertVault(VaultRow(id, server, account, bytes, manifest.dekVersion, root.id, folder.id, uploaded.id, now, now))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                throw EvidenceException(EvidenceErrorKind.NETWORK_UNAVAILABLE)
            }
            unlockedVault = EvidenceDb.currentVault(id)
            dek = key.copyOf()
            refreshStorage()
            Unit
        } finally {
            key.fill(0)
        }
    }

    override suspend fun unlock(password: String) = passwordMutex.withLock {
        val vault = localVault() ?: throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
        unlockRow(vault, password)
    }

    private suspend fun unlockRow(vault: VaultRow, password: String) {
        val restored = verifyRow(vault, password)
        unlockedVault = vault
        dek = restored
        EvidenceIdentity.save(vault.serverKey, vault.accountId)
        refreshStorage()
    }

    private suspend fun verifyRow(vault: VaultRow, password: String): ByteArray {
        val security = EvidenceDb.security(vault.serverKey, vault.accountId)
        val policy = AttemptPolicy(security.failedAttempts, security.cooldownUntilMs)
        if (policy.isCoolingDown()) throw EvidenceException(EvidenceErrorKind.COOLDOWN)
        try {
            if (!EvidencePasswordPolicy.isValid(password)) throw IllegalArgumentException("password length")
            val manifest = try { parseVaultManifest(vault.manifest) } catch (_: Throwable) { throw EvidenceException(EvidenceErrorKind.MANIFEST_INVALID) }
            val result = unlockVaultManifest(manifest, password)
            policy.recordSuccess()
            EvidenceDb.saveSecurity(vault.serverKey, vault.accountId, SecurityRow(0, 0))
            return result.dek
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            policy.recordFailure()
            EvidenceDb.saveSecurity(vault.serverKey, vault.accountId, SecurityRow(policy.snapshot().first, policy.snapshot().second))
            val kind = if (t is EvidenceException) t.kind else if (t is IllegalArgumentException) EvidenceErrorKind.PASSWORD_LENGTH else EvidenceErrorKind.PASSWORD_WRONG
            throw EvidenceException(kind, t)
        }
    }

    override suspend fun cooldownSeconds(): Long {
        val (server, account) = identity()
        return ((EvidenceDb.security(server, account).cooldownUntilMs - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1000
    }

    override fun lock() {
        if (recording.value.active) {
            lockRequested = true
            return
        }
        clearUnlocked()
    }

    private fun clearUnlocked() {
        dek?.fill(0)
        dek = null
        unlockedVault = null
        EvidenceFiles.playbackDir().listFiles()?.forEach { it.delete() }
        playbackTokens.clear()
        storage.value = emptyList()
    }

    override suspend fun start(mode: EvidenceMode, lens: EvidenceLens) {
        val vault = requireUnlocked()
        if (recording.value.active) throw EvidenceException(EvidenceErrorKind.SERVICE_START_DENIED)
        val required = buildList {
            if (mode == EvidenceMode.VIDEO) add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (required.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
            throw EvidenceException(if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) EvidenceErrorKind.NOTIFICATION_PERMISSION_REQUIRED else EvidenceErrorKind.PERMISSION_REQUIRED)
        }
        val session = EvidenceNames.sessionId(System.currentTimeMillis())
        val now = System.currentTimeMillis()
        EvidenceDb.insertSession(SessionRow(session, vault.vaultId, vault.serverKey, vault.accountId, mode, "RECORDING", now, null, null))
        val lease = EvidenceKeyBoxHolder.box.issue(requireNotNull(dek), now + 30_000)
        val intent = Intent(context, EvidenceRecordingService::class.java).apply {
            putExtra(EvidenceRecordingService.EXTRA_MODE, mode.name)
            putExtra(EvidenceRecordingService.EXTRA_LENS, lens.name)
            putExtra(EvidenceRecordingService.EXTRA_LEASE, lease)
            putExtra(EvidenceRecordingService.EXTRA_SESSION, session)
            putExtra(EvidenceRecordingService.EXTRA_VAULT, vault.vaultId)
            putExtra(EvidenceRecordingService.EXTRA_SERVER, vault.serverKey)
            putExtra(EvidenceRecordingService.EXTRA_ACCOUNT, vault.accountId)
            putExtra(EvidenceRecordingService.EXTRA_DEK_VERSION, vault.dekVersion)
        }
        try {
            EvidenceRecordingService.start(intent)
        } catch (t: Throwable) {
            EvidenceKeyBoxHolder.box.clear()
            EvidenceDb.deleteSessionRows(session)
            throw EvidenceException(EvidenceErrorKind.SERVICE_START_DENIED, t)
        }
    }

    override suspend fun stop() {
        val intent = Intent(context, EvidenceRecordingService::class.java).apply { action = EvidenceRecordingService.ACTION_STOP }
        context.startService(intent)
    }

    override suspend fun sessions(): List<EvidenceSessionView> {
        val vault = requireUnlocked()
        return EvidenceDb.sessions(vault.vaultId).map { session ->
            val rows = EvidenceDb.segments(session.sessionId)
            val segments = rows.map { row ->
                EvidenceSegmentView(row.key, if (row.mode == EvidenceMode.VIDEO) EvidenceMedia.VIDEO else EvidenceMedia.AUDIO, row.state, row.startedAtMs, row.durationMs, row.size, row.cipherPath.takeIf { !row.localDeleted && File(it).isFile }, row.cloudFileId, row.lastError)
            }
            EvidenceSessionView(session.sessionId, session.mode, session.startedAtMs, segments.size, segments.count { it.state == EvidenceSegmentState.UPLOADED }, segments.count { it.state == EvidenceSegmentState.FAILED }, rows.filterNot { it.localDeleted }.sumOf { it.size }, session.endedAtMs, session.state, segments)
        }
    }

    override suspend fun cloudVaults(): List<EvidenceCloudVault> = EvidenceCloudStore.cloudVaults()

    override suspend fun restore(vaultId: String, password: String) = passwordMutex.withLock {
        val cloud = cloudVaults().firstOrNull { it.vaultId == vaultId } ?: throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
        val bytes = try {
            EvidenceCloudStore.downloadManifest(cloud)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw EvidenceException(EvidenceErrorKind.NETWORK_UNAVAILABLE)
        }
        val manifest = try { parseVaultManifest(bytes) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Throwable) { throw EvidenceException(EvidenceErrorKind.MANIFEST_INVALID) }
        val (server, account) = identity()
        val existing = EvidenceDb.currentVault(vaultId)
        if (existing != null && (existing.serverKey != server || existing.accountId != account)) throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
        val root = EvidenceCloudStore.ensureRoot()
        val folder = EvidenceCloudStore.ensureVault(vaultId)
        val vault = VaultRow(vaultId, server, account, bytes, manifest.dekVersion, root.id, folder.id, cloud.manifestFileId, manifest.createdAtMs, manifest.updatedAtMs)
        val restored = verifyRow(vault, password)
        var imported = false
        try {
            val imports = mutableListOf<Pair<SessionRow, SegmentRow>>()
            val cloudSegments = try {
                EvidenceCloudStore.cloudSegments(vaultId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                throw EvidenceException(EvidenceErrorKind.NETWORK_UNAVAILABLE)
            }
            cloudSegments.forEach { (sessionFolder, file) ->
                val match = Regex("([0-9]{3})_(video\\.mp4|audio\\.m4a)\\.enc").matchEntire(file.name) ?: return@forEach
                val number = match.groupValues[1].toInt()
                if (number !in 1..999) return@forEach
                val mode = if (match.groupValues[2] == "video.mp4") EvidenceMode.VIDEO else EvidenceMode.AUDIO
                val dir = EvidenceFiles.sessionDir(vaultId, sessionFolder.name)
                val cipher = EvidenceFiles.cipherFile(dir, number, mode)
                val header = try {
                    val temp = File(dir, file.name + ".download")
                    try {
                        EvidenceCloudStore.downloadSegment(file.id, temp)
                        readSegmentHeader(temp).header
                    } finally {
                        temp.delete()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return@forEach
                }
                if (header.vaultId != vaultId || header.sessionId != sessionFolder.name || header.segmentNumber != number || header.dekVersion != manifest.dekVersion) return@forEach
                imports += SessionRow(sessionFolder.name, vaultId, server, account, mode, "IMPORTED", header.startedAtMs, header.startedAtMs + header.durationMs, sessionFolder.id) to SegmentRow(
                    vaultId, sessionFolder.name, number, server, account, mode, file.name,
                    EvidenceFiles.tempMedia(dir, number, mode).absolutePath, cipher.absolutePath,
                    file.size, null, file.sha256, header.startedAtMs, header.startedAtMs + header.durationMs,
                    header.durationMs, EVIDENCE_FORMAT_VERSION, header.dekVersion, EvidenceSegmentState.UPLOADED,
                    0, 0, null, file.id, header.startedAtMs, System.currentTimeMillis(),
                )
            }
            EvidenceDb.insertVault(vault)
            imports.forEach { (session, segment) ->
                EvidenceDb.insertSession(session)
                EvidenceDb.insertEncryptingSegment(segment)
            }
            unlockedVault = vault
            dek = restored
            imported = true
            EvidenceIdentity.save(server, account)
            refreshStorage()
        } finally {
            if (!imported) restored.fill(0)
        }
    }

    override suspend fun changePassword(oldPassword: String, newPassword: String) = passwordMutex.withLock {
        if (!EvidencePasswordPolicy.isValid(newPassword)) throw EvidenceException(EvidenceErrorKind.PASSWORD_LENGTH)
        val vault = requireUnlocked()
        val old = unlockVaultManifest(parseVaultManifest(vault.manifest), oldPassword).dek
        val key = dek ?: throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
        try {
            check(old.contentEquals(key))
            val manifest = createVaultManifest(vault.vaultId, newPassword, key, System.currentTimeMillis(), vault.createdAtMs)
            val bytes = manifest.signedBytes()
            EvidenceManifestSync.mutex.withLock {
                val uploadedManifest = EvidenceCloudStore.uploadManifest(vault.vaultId, bytes)
                EvidenceDb.replaceManifest(vault.vaultId, bytes, manifest.dekVersion, manifest.updatedAtMs)
                val root = EvidenceCloudStore.ensureRoot()
                val folder = EvidenceCloudStore.ensureVault(vault.vaultId)
                EvidenceDb.updateCloudIds(vault.vaultId, root.id, folder.id, uploadedManifest.id)
            }
            unlockedVault = EvidenceDb.currentVault(vault.vaultId)
        } finally { old.fill(0) }
    }

    override suspend fun deleteVault() {
        val vault = requireUnlocked()
        EvidenceManifestSync.mutex.withLock {
            EvidenceCloudStore.deleteVault(vault.vaultId)
            EvidenceDb.deleteVaultLocal(vault.vaultId)
        }
        lock()
    }

    override suspend fun playback(segmentKey: String): EvidencePlaybackRequest = withContext(Dispatchers.IO) {
        val key = SegmentKey.parse(segmentKey)
        val row = requireSegment(key)
        val cipher = ensureCipher(row)
        val token = EvidenceCrypto.randomBytes(16).toHex()
        val target = EvidenceFiles.playbackFile(token, if (row.mode == EvidenceMode.VIDEO) "mp4" else "m4a")
        decryptSegmentToCache(cipher, target, requireNotNull(dek))
        playbackTokens[token] = token to target.absolutePath
        EvidencePlaybackRequest(token, target.absolutePath, if (row.mode == EvidenceMode.VIDEO) EvidenceMedia.VIDEO else EvidenceMedia.AUDIO)
    }

    override fun closePlayback(token: String) {
        val media = playbackTokens.remove(token) ?: return
        File(media.second).delete()
    }

    private val playbackTokens = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    override suspend fun exportRequest(segmentKey: String): FileSaveRequest? = withContext(Dispatchers.IO) {
        val request = playback(segmentKey)
        playbackTokens[request.token] = request.token to request.path
        FileSaveRequest(request.token, "evidence-${request.token}.${if (request.media == EvidenceMedia.VIDEO) "mp4" else "m4a"}", if (request.media == EvidenceMedia.VIDEO) "video/mp4" else "audio/mp4", File(request.path).length(), open = { File(request.path).inputStream().toByteReadChannel() })
    }

    override suspend fun deleteLocalSegments(segmentKeys: List<String>) = withContext(Dispatchers.IO) {
        val vault = requireUnlocked()
        segmentKeys.map(SegmentKey::parse).forEach { key ->
            if (key.vaultId != vault.vaultId) throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
            EvidenceDb.deleteLocalSegment(key)
        }
        refreshStorage()
    }

    private fun requireUnlocked(): VaultRow {
        val vault = unlockedVault ?: throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
        if (dek == null) throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
        return vault
    }

    private fun requireSegment(key: SegmentKey): SegmentRow {
        val vault = requireUnlocked()
        if (key.vaultId != vault.vaultId) throw EvidenceException(EvidenceErrorKind.DECRYPT_FAILED)
        return EvidenceDb.segment(key) ?: throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
    }

    private suspend fun ensureCipher(row: SegmentRow): File {
        val file = File(row.cipherPath)
        if (file.isFile) return file
        val cloudId = row.cloudFileId ?: throw EvidenceException(EvidenceErrorKind.VAULT_NOT_FOUND)
        EvidenceCloudStore.downloadSegment(cloudId, file)
        return file
    }

    private fun refreshStorage() {
        val vault = unlockedVault ?: return
        storage.value = EvidenceDb.sessions(vault.vaultId).map { session ->
            val rows = EvidenceDb.segments(session.sessionId).filterNot { it.localDeleted }
            EvidenceStorageSummary(session.sessionId, rows.size, rows.sumOf { it.size })
        }
    }
}
