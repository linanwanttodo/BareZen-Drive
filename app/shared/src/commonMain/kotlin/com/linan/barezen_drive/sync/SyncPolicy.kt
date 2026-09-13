package com.linan.barezen_drive.sync

/** Lifecycle of one device media item in the sync queue. */
enum class SyncState(val key: String) {
    PENDING("pending"),
    UPLOADING("uploading"),
    DONE("done"),
    FAILED("failed");

    companion object {
        fun fromKey(k: String): SyncState = entries.firstOrNull { it.key == k } ?: PENDING
    }
}

/**
 * Framework-free decision logic for the album sync queue.
 *
 * Everything here is a pure function, deliberately: the Android side of the
 * queue is built on SQLiteOpenHelper / Cursor / WorkManager, none of which can
 * run in this project's `androidHostTest` source set (it is a plain JVM test,
 * with no Robolectric on the classpath). Separating *policy* from *plumbing* is
 * what makes the queue's behaviour verifiable at all - see SyncPolicyTest.
 *
 * The Android files (SyncDb, SyncScan, SyncUploadQueue) call into this object
 * instead of re-deriving the rules inline, so the rules exist in exactly one
 * place.
 */
object SyncPolicy {

    /** Retry ceiling per item. Past this a FAILED row stops being picked up; the
     *  daily reconciler does not reset it, so a permanently unreadable file (a
     *  file the user moved away mid-scan) cannot loop forever. */
    const val MAX_ATTEMPTS = 5

    /** Upload lanes on an unmetered link (Wi-Fi, Ethernet). Two keeps a large
     *  video from monopolising the pass while staying friendly to a home server. */
    const val LANES_UNMETERED = 2

    /** Upload lanes on a metered link. One lane: concurrent 20 MiB chunks are
     *  exactly what makes a phone's mobile data feel unusable to everything else
     *  running on it, and the user opted into a constrained link. */
    const val LANES_METERED = 1

    /** Concurrency for the link the pass is running on. */
    fun lanesFor(unmetered: Boolean): Int = if (unmetered) LANES_UNMETERED else LANES_METERED

    /** Consecutive transport failures that abort a pass: the link is down, so
     *  grinding through the rest of the queue would only burn their budgets. */
    const val NETWORK_FAILURE_LIMIT = 2

    /** Incremental scans re-read this much before the watermark, so a
     *  same-second write or a skewed device clock cannot fall through the crack.
     *  A duplicate row is harmless - upsertScanned is idempotent. */
    const val SCAN_OVERLAP_SECONDS = 300L

    /** Hidden directories and long hex hashes (WeChat and friends) must not
     *  become category folders. Note this decides *categorisation* only: a null
     *  result means "upload straight into the device folder", not "skip".
     *  Excluding whole albums is the user's job, via the bucket picker. */
    private val CACHE_ALBUM = Regex("[0-9a-fA-F]{16,}")

    fun albumCategory(name: String?): String? =
        name?.takeIf { it.isNotBlank() && !it.startsWith(".") && !CACHE_ALBUM.matches(it) }

    /** A DONE row whose size or modification time moved on was edited on the
     *  device and has to go back into the queue (with its cached hash dropped). */
    fun isStale(
        state: SyncState,
        storedSize: Long,
        storedDateModified: Long,
        scannedSize: Long,
        scannedDateModified: Long,
    ): Boolean = state == SyncState.DONE &&
        (storedSize != scannedSize || storedDateModified != scannedDateModified)

    /** PENDING is always due; FAILED is due while it still has retries left. */
    fun isDue(state: SyncState, attempts: Int, maxAttempts: Int = MAX_ATTEMPTS): Boolean =
        when (state) {
            SyncState.PENDING -> true
            SyncState.FAILED -> attempts < maxAttempts
            else -> false
        }

    /**
     * Transport-level failures - no route, DNS, socket timeout - which a retry
     * can plausibly fix. A server-side rejection (404, 409, 400) is *not*
     * included: retrying those only wastes battery, so they consume the item's
     * retry budget instead of stopping the pass.
     */
    fun isNetworkFailure(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return m.contains("failed to connect") ||
            m.contains("unable to resolve host") ||
            m.contains("network is unreachable") ||
            m.contains("timeout") ||
            m.contains("timed out") ||
            m.contains("connection reset") ||
            m.contains("broken pipe") ||
            m.contains("connection refused") ||
            m.contains("software caused connection abort")
    }

    /** See [NETWORK_FAILURE_LIMIT]. */
    fun shouldStopPass(consecutiveNetworkFailures: Int): Boolean =
        consecutiveNetworkFailures >= NETWORK_FAILURE_LIMIT

    /**
     * MediaStore selection argument for an incremental scan, or null for a full
     * one. [lastScanAtMillis] is when the previous scan *finished*; the overlap
     * is subtracted here so callers pass the raw watermark.
     */
    fun scanSinceSeconds(lastScanAtMillis: Long?): Long? {
        val at = lastScanAtMillis?.takeIf { it > 0 } ?: return null
        return (at / 1000L - SCAN_OVERLAP_SECONDS).coerceAtLeast(0L)
    }

    /** Due rows before retries: photos first, then oldest modification time, so
     *  a freshly taken photo lands near the front of the next pass. */
    fun dueOrder(): String = "is_photo DESC, date_modified ASC"
}
