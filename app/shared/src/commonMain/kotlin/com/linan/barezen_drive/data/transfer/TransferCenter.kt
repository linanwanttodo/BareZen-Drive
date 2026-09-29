package com.linan.barezen_drive.data.transfer

import com.linan.barezen_drive.data.library.LibraryRevision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

/** What a transfer row represents. */
enum class TransferKind { UPLOAD, DOWNLOAD, SYNC }

/**
 * Which transfer page a row belongs to. Album work (sync batches and manual
 * photo picks from the album tab) is listed on the album page's transfer
 * screen; everything else (file uploads, downloads) belongs to the file
 * transfer screen reached from home and files. The two never mix.
 */
enum class TransferLane { ALBUM, FILE }

enum class TransferPhase { QUEUED, RUNNING, DONE, FAILED }

/**
 * Enough to run the same upload again: the picked source plus the two
 * arguments that change the server's answer. Held beside the transfer (not
 * inside [TransferItem]) so the list state stays a cheap value type - a
 * PickedFile is a File/URI reference, not the bytes.
 */
data class RetryHandle(
    val file: com.linan.barezen_drive.platform.PickedFile,
    val folderId: String?,
    val overwrite: Boolean,
)

data class TransferItem(
    val id: String,
    val name: String,
    val kind: TransferKind,
    val phase: TransferPhase,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    /** Short status line under the name, e.g. "3 / 12" for batch syncs. */
    val statusText: String? = null,
    /** Set on file rows that belong to a batch (the id of the batch row). */
    val parent: String? = null,
    /** Server file id once the upload completed - the thumbnail tile's source. */
    val fileId: String? = null,
    /** Image-like when the name maps to an image/video type, for the tile icon. */
    val mediaHint: Boolean = false,
    val error: String? = null,
    /**
     * Set while the row can be retried (single-file uploads only). Lives on
     * the item rather than in a side map so it rides the same atomic StateFlow
     * update as everything else - the upload coroutine writes it while the UI
     * reads - and it is released automatically when the row is cleared.
     */
    val retry: RetryHandle? = null,
    val atMs: Long = 0,
    /** Owning transfer page; sync work is always album-side. */
    val lane: TransferLane = if (kind == TransferKind.SYNC) TransferLane.ALBUM else TransferLane.FILE,
)

/**
 * In-memory transfer registry behind the transfer-centre screen.
 *
 * Uploads report here through UploadManager; the album "sync now" action and
 * downloads report here too. Entries are kept for the session (a durable
 * history is a later increment) and exposed as one StateFlow so the three
 * tabs - active uploads, active downloads, finished - are a pure projection.
 */
object TransferCenter {
    private const val MAX_HISTORY = 100

    private val _items = MutableStateFlow<List<TransferItem>>(emptyList())
    val items: StateFlow<List<TransferItem>> = _items

    private var seq = 0L

    // Batch cancellations requested from the UI; workers poll this between
    // files and stop the queue.
    private val cancelledIds = mutableSetOf<String>()

    /** UI stop button: asks a running batch to finish after the current file. */
    fun cancel(id: String) {
        cancelledIds.add(id)
    }

    fun isCancelled(id: String): Boolean = id in cancelledIds

    /**
     * Marks every running sync batch as cancelled - the "stop syncing" action.
     * Workers poll [isCancelled] between files, so the in-flight upload finishes
     * and the rest of the queue stays PENDING for the next trigger.
     */
    fun cancelSyncBatches() {
        val ids = _items.value
            .filter { it.kind == TransferKind.SYNC && it.parent == null && it.phase == TransferPhase.RUNNING }
            .map { it.id }
        cancelledIds.addAll(ids)
    }

    /** Adds a queued file row under a batch (Google-Photos backup queue). */
    fun queueFile(batchId: String, name: String, size: Long, mediaHint: Boolean = false): String {
        val id = "t${++seq}"
        push(
            TransferItem(id, name, TransferKind.SYNC, TransferPhase.QUEUED, 0, size, parent = batchId, mediaHint = mediaHint, atMs = nowMs()),
        )
        return id
    }

    fun fileStart(id: String) {
        update(id) { it.copy(phase = TransferPhase.RUNNING, statusText = null) }
    }

    /** When a batch ends (done/fail/cancel), its queued children settle too. */
    private fun settleChildren(batchId: String, phase: TransferPhase, error: String?) {
        _items.update { list ->
            list.map {
                if (it.parent == batchId && (it.phase == TransferPhase.QUEUED || it.phase == TransferPhase.RUNNING)) {
                    it.copy(phase = phase, error = error)
                } else {
                    it
                }
            }
        }
    }

    fun start(
        name: String,
        kind: TransferKind,
        total: Long,
        lane: TransferLane = if (kind == TransferKind.SYNC) TransferLane.ALBUM else TransferLane.FILE,
    ): String {
        val id = "t${++seq}"
        push(TransferItem(id, name, kind, TransferPhase.RUNNING, 0, total, atMs = nowMs(), lane = lane))
        // System notification too: netdisks show progress in the shade so the
        // user can leave the app. Sync batches are the exception - the album
        // backup already owns a foreground-service notification while it runs,
        // and a second entry per batch is how the shade used to end up with
        // duplicate rows for one backup.
        if (kind != TransferKind.SYNC) {
            runCatching {
                com.linan.barezen_drive.platform.TransferNotifier.showProgress(
                    tag = id,
                    title = name,
                    text = null,
                    fraction = if (total > 0) 0f else null,
                )
            }
        }
        return id
    }

    /**
     * Retries run the same upload again, so the picked source has to outlive
     * the attempt that failed. Kept in a side map keyed by transfer id, and
     * dropped as soon as the row is cleared or cancelled, so nothing pins a
     * file handle for longer than the transfer itself.
     */
    /** Called by the uploader right after [start] for single-file uploads. */
    fun rememberRetryHandle(id: String, handle: RetryHandle) {
        update(id) { it.copy(retry = handle) }
    }

    /** Null when the row cannot be retried (batch sync, or already cleared). */
    fun retryHandle(id: String): RetryHandle? =
        _items.value.firstOrNull { it.id == id }?.retry

    /** Clears a stale error before a retry re-runs, so the row stops reading failed. */
    fun clearError(id: String) {
        update(id) { it.copy(error = null, phase = TransferPhase.RUNNING, bytesDone = 0) }
    }

    /**
     * @param reset starts the count from zero even if the row already advanced -
     *   a retry re-sends the whole file, and without this the bar would sit at
     *   the previous attempt's position while the bytes go up from nothing.
     */
    fun progress(id: String, done: Long, total: Long, text: String? = null, reset: Boolean = false) {
        var title = ""
        var fraction: Float? = null
        var notify = true
        update(id) {
            val t = if (total > 0) total else it.bytesTotal
            // Never rewind. A re-sent chunk, a retried range or two writers on
            // the same row would otherwise walk the bar backwards, and a bar
            // that goes backwards is worse than no bar: it reads as a bug.
            val advanced = if (reset) done else maxOf(done, it.bytesDone)
            title = it.name
            fraction = if (t > 0) (advanced.toFloat() / t).coerceIn(0f, 1f) else null
            // Sync batches never own a shade entry (the foreground backup
            // notification is the single source) - re-showing here would
            // resurrect the duplicate the start() path just removed.
            notify = it.kind != TransferKind.SYNC
            it.copy(bytesDone = advanced, bytesTotal = t, statusText = text ?: it.statusText)
        }
        if (notify) {
            runCatching { com.linan.barezen_drive.platform.TransferNotifier.showProgress(id, title, text, fraction) }
        }
    }

    fun done(id: String, fileId: String? = null) {
        cancelledIds.remove(id)
        val item = _items.value.firstOrNull { it.id == id } ?: return
        update(id) { it.copy(phase = TransferPhase.DONE, atMs = nowMs(), fileId = fileId ?: it.fileId) }
        // The cover may exist from now on: lift the negative mark so list tiles
        // refetch. markAvailable, not invalidate - the upload pipeline feeds
        // the fresh cover bytes straight into the cache, and wiping the cache
        // entry here would throw that away and force a needless round-trip.
        fileId?.let { com.linan.barezen_drive.ui.media.ThumbnailHub.markAvailable(it) }
        // A server-assigned id means a new file exists, which is the one event
        // every library listing has to react to. This is the single choke point
        // for both lanes: the file uploader reports here, and so does every
        // per-photo upload of an album backup (which runs in a worker, with the
        // UI parked anywhere). Batch rows themselves carry no id - they only
        // wrap the files.
        if (fileId != null) LibraryRevision.bump()
        runCatching {
            if (item.kind == TransferKind.SYNC) {
                // A finished backup is not news: dismiss the progress entry
                // instead of stacking a result notification on top of it.
                settleChildren(id, TransferPhase.DONE, null)
            } else {
                com.linan.barezen_drive.platform.TransferNotifier.dismiss(id)
            }
        }
    }

    fun fail(id: String, error: String) {
        cancelledIds.remove(id)
        val item = _items.value.firstOrNull { it.id == id } ?: return
        update(id) { it.copy(phase = TransferPhase.FAILED, error = error, atMs = nowMs()) }
        if (item.kind == TransferKind.SYNC) {
            settleChildren(id, TransferPhase.FAILED, error)
        } else {
            runCatching { com.linan.barezen_drive.platform.TransferNotifier.showFinished(id, item.name, error, ok = false) }
        }
    }

    /**
     * Drops a row that never really started - the user left the save dialog, so
     * no bytes moved and there is nothing to report. Failing it would show a
     * red row for a download they deliberately stopped, and leaving it running
     * would show a bar that never moves.
     */
    fun discard(id: String) {
        cancelledIds.remove(id)
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.phase == TransferPhase.DONE || item.phase == TransferPhase.FAILED) return
        _items.value = _items.value.filterNot { it.id == id }
        runCatching { com.linan.barezen_drive.platform.TransferNotifier.dismiss(id) }
    }

    /** Removes finished rows of one lane (the "clear" action on the done tab). */
    fun clearFinished(lane: TransferLane? = null) {
        // Only settled rows: queued children of a still-running batch must
        // stay visible. A cancel flag whose batch has left the list is also
        // dropped, keeping cancelledIds bounded to live work.
        fun settled(it: TransferItem) =
            (it.phase == TransferPhase.DONE || it.phase == TransferPhase.FAILED) &&
                (lane == null || it.lane == lane)
        val finished = _items.value.filter(::settled)
        _items.update { list -> list.filterNot(::settled) }
        finished.forEach {
            cancelledIds.remove(it.id)
            // The row is gone, so the retained source handle goes with it.
            runCatching { com.linan.barezen_drive.platform.TransferNotifier.dismiss(it.id) }
        }
    }

    private fun push(item: TransferItem) {
        _items.update { list ->
            // Live batch headers survive the history cap: a first backup with
            // more than MAX_HISTORY photos would otherwise evict its own batch
            // row, which turns "stop syncing" into a no-op (it scans for
            // RUNNING batch rows) and strands the queued children without
            // their settle path. Everything else still truncates, newest first.
            val combined = listOf(item) + list
            val liveBatches = combined
                .filter {
                    it.kind == TransferKind.SYNC && it.parent == null &&
                        it.phase == TransferPhase.RUNNING
                }
                .map { it.id }
                .toSet()
            var budget = MAX_HISTORY
            combined.filter {
                when {
                    it.id in liveBatches -> true
                    budget > 0 -> { budget--; true }
                    else -> false
                }
            }
        }
    }

    private fun update(id: String, block: (TransferItem) -> TransferItem) {
        _items.update { list -> list.map { if (it.id == id) block(it) else it } }
    }

    // A real wall-clock timestamp: it is what the UI renders ("3 分钟前") and
    // what orders rows across lanes, so a counter would be wrong. The seq
    // counter above stays reserved for ids ("t<n>"), where only uniqueness
    // matters and the value never shows.
    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}
