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
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.platform.AndroidPickedFile
import com.linan.barezen_drive.platform.deviceName
import com.linan.barezen_drive.platform.legacyDeviceName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Image/video by extension: drives the media tile on transfer rows whose
 *  server file id (and therefore real cover) only exists after the upload. */
internal fun isMediaName(name: String): Boolean {
    val lower = name.substringAfterLast('.', "").lowercase()
    return lower in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "mp4", "mov", "webm", "m4v", "3gp", "avi", "mkv")
}

/** Outcome of one concurrent sync pass, reported up to the worker and the
 *  foreground notification. */
data class SyncPass(val done: Int, val failed: Int, val stoppedEarly: Boolean)

/**
 * Runs the pending queue with bounded concurrency (feature 4).
 *
 * Photos are tried before videos (the queue orders them); the caller decides how
 * many files upload at once ([lanes] - two on Wi-Fi, one on mobile data) and the
 * work is handed out by a paged feed every lane reads from. Metered-data control
 * lives in the WorkManager constraints (the periodic job is UNMETERED when the
 * user picked Wi-Fi-only), so a pass that is already running can go full speed.
 * Each lane owns its own [UploadManager] because that class keeps single-slot
 * progress state.
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
        // stoppedEarly is what makes the worker return retry(): an unreachable
        // server (or a still-migrating album root) must re-drive the queue on
        // the request's backoff, not park it until the next periodic run.
        val deviceFolder = AlbumFolder.resolve(repo, deviceName(), legacyDeviceName())
            ?: return@coroutineScope SyncPass(0, 0, stoppedEarly = true)

        // The rows come from [DueFeed] a page at a time, so the total the card and
        // the notification show is a COUNT rather than the size of a materialised
        // list. Excluded albums are already filtered out of both queries.
        val total = SyncDb.dueCount()
        if (total == 0) return@coroutineScope SyncPass(0, 0, stoppedEarly = false)
        val feed = DueFeed(DUE_PAGE_SIZE)

        val doneCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)
        val skippedCount = AtomicInteger(0)
        val consecutiveNet = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        // Resolved so far. Skipped rows count: they are answered, and leaving them
        // out would park the bar short of the end.
        val processed = { doneCount.get() + failCount.get() + skippedCount.get() }

        val batchTitle = I18n.strings.syncBatchTitle
        val batchId = TransferCenter.start(batchTitle, TransferKind.SYNC, total.toLong())

        repeat(lanes.coerceAtLeast(1)) {
            launch(Dispatchers.IO) {
                // One manager per lane, still. The reason it used to be
                // mandatory is gone - progress used to live in a single slot on
                // the manager, so two concurrent uploads on one instance
                // overwrote each other's row and cancelling one aborted the
                // other's server session. Progress is now per call and the
                // session belongs to the call that opened it, so sharing an
                // instance across lanes would be safe. Kept as-is because
                // per-lane isolation is still the simpler thing to reason
                // about, and changing it buys nothing but churn.
                val uploader = UploadManager(repo)
                while (!stop.get()) {
                    val item = feed.next() ?: break
                    if (TransferCenter.isCancelled(batchId)) {
                        stop.set(true)
                        break
                    }
                    // The queue is walked live, but a pass can run for hours and
                    // an album excluded since a row was read must not be uploaded
                    // from a stale page. The first-run review is exactly when that
                    // happens: the user is unchecking a multi-gigabyte cache album
                    // while the very first pass is already draining. One
                    // primary-key lookup per item is noise next to a 20 MiB
                    // upload. The row stays PENDING so re-including the album can
                    // pick it up again (that is what re-drives the queue).
                    if (SyncDb.isBucketExcluded(item.bucket)) {
                        skippedCount.incrementAndGet()
                        onProgress(processed(), maxOf(total, processed()))
                        continue
                    }
                    // A transfer row per file the pass actually reaches, created as
                    // it is claimed: pushing one for every queued photo used to
                    // cost 50k list copies before a single byte had been read.
                    val childId = TransferCenter.queueFile(
                        batchId, item.name, item.size, mediaHint = isMediaName(item.name),
                    )
                    SyncDb.markUploading(item.uri)
                    TransferCenter.fileStart(childId)
                    val target = item.bucket?.takeIf { it.isNotBlank() }
                        ?.let { AlbumFolder.resolveCategory(repo, deviceFolder, it) }
                        ?: deviceFolder
                    val picked = AndroidPickedFile(ctx, Uri.parse(item.uri))
                    // Persist a freshly computed hash on the terminal row so a
                    // later retry of an unchanged file skips the whole-file read.
                    var freshHash: String? = null
                    // Not runCatching: a cancelled pass must not look like a
                    // failed upload. Swallowing the cancellation let "stop
                    // sync" walk the rest of the snapshot, mark every remaining
                    // row FAILED and burn one of its retries for a request that
                    // was never even sent.
                    val outcome = try {
                        Result.success(
                            uploader.upload(
                                picked, target,
                                reportTransfer = false,
                                cachedSha256 = item.hashCache,
                                onHashed = { h -> freshHash = h },
                            )
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Result.failure(t)
                    }
                    val cachedHash = item.hashCache ?: freshHash
                    outcome.fold(
                        onSuccess = { result ->
                            result.fold(
                                onSuccess = { dto ->
                                    SyncDb.markDone(item.uri, dto.id, cachedHash ?: dto.sha256)
                                    consecutiveNet.set(0)
                                    doneCount.incrementAndGet()
                                    TransferCenter.done(childId, dto.id)
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
                    onProgress(processed(), maxOf(total, processed()))
                }
            }
        }
        // Lanes exit when the feed runs dry or stop flips; coroutineScope joins.
        val done = doneCount.get()
        val failed = failCount.get()
        if (stop.get()) {
            TransferCenter.fail(batchId, I18n.strings.syncBatchNetworkLost)
        } else if (failed == 0) {
            TransferCenter.done(batchId)
        } else {
            TransferCenter.fail(batchId, I18n.strings.syncBatchFailed(failed, done))
        }
        SyncPass(done, failed, stoppedEarly = stop.get())
    }

    private fun recordFailure(        item: SyncItem,
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

/** Rows read per queue query. Small enough to hold in memory while a pass runs
 *  for hours, large enough that a 50k-photo queue costs 200 reads, not 200 rounds
 *  of per-row work. */
private const val DUE_PAGE_SIZE = 256

/**
 * Feeds one pass the due rows, a page at a time, from a walk every lane shares.
 *
 * The pass used to materialise the whole pending library before uploading the
 * first file: tens of megabytes of rows held for the hours a first backup takes.
 * Pages are walked with the queue's own keyset cursor, not OFFSET, because rows
 * leave the due set as they are uploaded - with OFFSET that would shift the
 * window and silently skip the row that took the freed slot.
 */
private class DueFeed(private val pageSize: Int) {
    private val lock = Mutex()
    private var page: List<SyncItem> = emptyList()
    private var index = 0
    private var after: SyncPolicy.DueCursor? = null
    private var exhausted = false

    /** The next row to try, or null once the queue is drained. */
    suspend fun next(): SyncItem? = lock.withLock {
        while (!exhausted && index >= page.size) {
            page = SyncDb.duePage(after, pageSize)
            index = 0
            if (page.isEmpty()) exhausted = true
        }
        if (index >= page.size) {
            null
        } else {
            val item = page[index++]
            after = SyncPolicy.DueCursor(item.isPhoto, item.dateModified, item.uri)
            item
        }
    }
}
