package com.linan.barezen_drive.evidence

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.linan.barezen_drive.data.local.TokenStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class EvidenceUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val vaults = EvidenceDb.allVaults()
        for (vault in vaults) {
            val identity = try {
                currentIdentity()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                continue
            }
            if (identity.first != vault.serverKey || identity.second != vault.accountId) continue
            val due = EvidenceDb.dueSegments(vault.serverKey, vault.accountId, now, 4)
            try {
                EvidenceManifestSync.mutex.withLock {
                    val current = EvidenceDb.currentVault(vault.vaultId) ?: return@withLock
                    if (current.manifestFileId.isNullOrBlank()) {
                        val manifest = EvidenceCloudStore.uploadManifest(current.vaultId, current.manifest)
                        EvidenceDb.updateCloudIds(current.vaultId, EvidenceCloudStore.ensureRoot().id, EvidenceCloudStore.ensureVault(current.vaultId).id, manifest.id)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                due.forEach { EvidenceDb.markSegmentFailed(it.key, "manifest upload failed", now + UploadBackoff.delayMs(it.attempts + 1), now) }
                continue
            }
            for (row in due) {
                try {
                    val uploaded = EvidenceCloudStore.uploadSegment(row)
                    EvidenceDb.markSegmentUploaded(row.key, uploaded.id, System.currentTimeMillis())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    val at = System.currentTimeMillis()
                    EvidenceDb.markSegmentFailed(row.key, t::class.simpleName ?: "upload failed", at + UploadBackoff.delayMs(row.attempts + 1), at)
                }
            }
        }
        Result.success()
    }

    private suspend fun currentIdentity(): Pair<String, String> {
        val user = AppClientHolder.files.me().getOrThrow()
        val server = EvidenceCrypto.sha256(TokenStorage.baseUrl.trimEnd('/').encodeToByteArray()).toHex()
        return server to user.id
    }
}

internal object EvidenceManifestSync {
    val mutex = Mutex()
}

object EvidenceUploadScheduler {
    fun enqueue(context: Context) {
        val work = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<EvidenceUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniqueWork("evidence_upload_now", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun apply(context: Context) {
        val request = PeriodicWorkRequestBuilder<EvidenceUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("evidence_upload_periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

private object AppClientHolder {
    val files get() = com.linan.barezen_drive.data.api.AppClient.files
}
