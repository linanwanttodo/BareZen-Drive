package com.linan.barezen_drive.sync

import android.content.Context
import android.net.Uri
import com.linan.barezen_drive.AndroidContext
import com.linan.barezen_drive.data.api.ApiClient
import com.linan.barezen_drive.data.api.ApiFailure
import com.linan.barezen_drive.data.repo.AlbumFolder
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.data.transfer.TransferCenter
import com.linan.barezen_drive.data.transfer.TransferKind
import com.linan.barezen_drive.data.upload.UploadManager
import com.linan.barezen_drive.platform.AndroidPickedFile
import com.linan.barezen_drive.platform.deviceName
import com.linan.barezen_drive.platform.legacyDeviceName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Outcome of one concurrent sync pass, reported up to the worker and the
 *  foreground notification. */
data class SyncPass(val done: Int, val failed: Int, val stoppedEarly: Boolean)

/**
 * Runs the pending queue with bounded concurrency (feature 4).
 *
 * Photos are tried before videos (the queue orders them); the caller decides how
 * many files upload at once ([lanes] - two on Wi-Fi, one on mobile data) and the
 * work is handed out through a shared cursor. Metered-data control lives in the
 * WorkManager constraints (the periodic job is UNMETERED when the user picked
 * Wi-Fi-only), so a pass that is already running can go full speed. Each lane owns
 * its own [UploadManager] because that class keeps single-slot progress state.
 *
 * [SyncPolicy.NETWORK_FAILURE_LIMIT] consecutive *network* errors stop the pass
 * early; the items never reached stay PENDING, so a lost connection during a big
 * backup does not burn every item's retry budget in one go.
 */
object SyncUploadQueue {

    // The progress callback is suspend so the worker can refresh its foreground
    // notification (setForeground is itself a suspend call) from inside a pass.
    suspend fun runPass(lanes: Int, onProgress: suspend (Int, Int) -> Unit): SyncPass = coroutineScope {
        val ctx: Context = AndroidContext.app
        val repo = FilesRepository(ApiClient())
        val deviceFolder = AlbumFolder.resolve(repo, deviceName(), legacyDeviceName())
            ?: return@coroutineScope SyncPass(0, 0, stoppedEarly = false)

        // One list, one moving cursor. The earlier Channel-based version pushed
        // the queue in and then drained it straight back out just to learn the
        // total, holding two copies of every row; the total is the list size, and
        // SyncDb.due() has already applied the excluded-album filter.
        val pending = SyncDb.due()
        val total = pending.size
        if (total == 0) return@coroutineScope SyncPass(0, 0, stoppedEarly = false)
        val cursor = AtomicInteger(0)

        val doneCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val skippedCount = AtomicInteger(0)
        val consecutiveNet = AtomicInteger(0)
        val stop = AtomicBoolean(false)

        val batchTitle = if (java.util.Locale.getDefault().language == "zh") "相册同步" else "Album sync"
        val batchId = TransferCenter.start(batchTitle, TransferKind.SYNC, total.toLong())
        val childIds = pending.associate { it.uri to TransferCenter.queueFile(batchId, it.name, it.size) }

        repeat(lanes.coerceAtLeast(1)) {
            launch(Dispatchers.IO) {
                // One manager per lane: its progress StateFlow and activeUploadId
                // are single-slot and must not be shared across concurrent files.
                val uploader = UploadManager(repo)
                while (!stop.get()) {
                    // claim-then-check: getAndIncrement can hand out an index past
                    // the end, so the bound is tested after claiming.
                    val index = cursor.getAndIncrement()
                    if (index >= total) break
                    val item = pending[index]
                    if (TransferCenter.isCancelled(batchId)) {
                        stop.set(true)
                        break
                    }
                    // The queue was snapshotted when the pass started, but a pass
                    // can run for hours. An album excluded since then - the
                    // first-run review is exactly when that happens, and the user
                    // is unchecking a multi-gigabyte cache album while the very
                    // first pass is already draining - must not be uploaded from
                    // a stale snapshot, so every item is re-checked against the
                    // live exclusion set. One primary-key lookup per item is
                    // noise next to a 20 MiB upload. The row stays PENDING so
                    // re-including the album can pick it up again (that is what
                    // re-drives the queue); only the transfer-centre row is
                    // resolved here.
                    if (SyncDb.isBucketExcluded(item.bucket)) {
                        skippedCount.incrementAndGet()
                        childIds[item.uri]?.let { TransferCenter.done(it) }
                        onProgress(doneCount.get() + failCount.get() + skippedCount.get(), total)
                        continue
                    }
                    SyncDb.markUploading(item.uri)
                    val childId = childIds[item.uri]
                    childId?.let { TransferCenter.fileStart(it) }
                    val target = item.bucket?.takeIf { it.isNotBlank() }
                        ?.let { AlbumFolder.resolveCategory(repo, deviceFolder, it) }
                        ?: deviceFolder
                    val picked = AndroidPickedFile(ctx, Uri.parse(item.uri))
                    // Persist a freshly computed hash on the terminal row so a
                    // later retry of an unchanged file skips the whole-file read.
                    var freshHash: String? = null
                    val outcome = runCatching {
                        uploader.upload(
                            picked, target,
                            reportTransfer = false,
                            cachedSha256 = item.hashCache,
                            onHashed = { h -> freshHash = h },
                        )
                    }
                    val cachedHash = item.hashCache ?: freshHash
                    outcome.fold(
                        onSuccess = { result ->
                            result.fold(
                                onSuccess = { dto ->
                                    SyncDb.markDone(item.uri, dto.id, cachedHash ?: dto.sha256)
                                    consecutiveNet.set(0)
                                    doneCount.incrementAndGet()
                                    childId?.let { TransferCenter.done(it) }
                                },
                                onFailure = { err ->
                                    recordFailure(item, err, cachedHash, childId, consecutiveNet, failCount, stop)
                                },
                            )
                        },
                        onFailure = { err ->
                            recordFailure(item, err, cachedHash, childId, consecutiveNet, failCount, stop)
                        },
                    )
                    // Skipped rows count toward progress: they are resolved, and
                    // leaving them out would park the bar short of the end.
                    onProgress(doneCount.get() + failCount.get() + skippedCount.get(), total)
                }
            }
        }
        // Lanes exit when the cursor runs out or stop flips; coroutineScope joins.
        val done = doneCount.get()
        val failed = failCount.get()
        if (stop.get()) {
            TransferCenter.fail(batchId, if (java.util.Locale.getDefault().language == "zh") "网络中断，稍后自动续传" else "network lost, retrying later")
        } else if (failed == 0) {
            TransferCenter.done(batchId)
        } else {
            TransferCenter.fail(batchId, if (java.util.Locale.getDefault().language == "zh") "$failed 张失败，已同步 $done 张" else "$failed failed, $done synced")
        }
        SyncPass(done, failed, stoppedEarly = stop.get())
    }

    private fun recordFailure(
        item: SyncItem,
        err: Throwable,
        cachedHash: String?,
        childId: String?,
        consecutiveNet: AtomicInteger,
        failCount: AtomicInteger,
        stop: AtomicBoolean,
    ) {
        SyncDb.markFailed(item.uri, err.message, cachedHash)
        childId?.let { TransferCenter.fail(it, err.message ?: "failed") }
        failCount.incrementAndGet()
        val isNetwork = err is ApiFailure.Network || SyncPolicy.isNetworkFailure(err.message)
        if (isNetwork) {
            if (SyncPolicy.shouldStopPass(consecutiveNet.incrementAndGet())) stop.set(true)
        } else {
            consecutiveNet.set(0)
        }
    }
}
