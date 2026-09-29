package com.linan.barezen_drive.ui.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.platform.isWebPlatform
import com.linan.barezen_drive.platform.monotonicNowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * Process-wide handle to the active [ThumbnailLoader], so layers that never see
 * the composition tree (transfer registry, upload pipeline) can invalidate or
 * read covers. Registered once from the app shell next to the loader instance.
 */
object ThumbnailHub {
    @Volatile
    private var loader: ThumbnailLoader? = null

    fun register(instance: ThumbnailLoader) {
        loader = instance
    }

    /** Clears the file's cached/negative thumbnail state (no-op before register). */
    fun invalidate(fileId: String) {
        val l = loader ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            l.invalidate(fileId)
        }
    }

    /** Drops only the negative entry: the cover may exist server-side now, so
     *  tiles should refetch. Any already-cached bitmap is kept. */
    fun markAvailable(fileId: String) {
        val l = loader ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            l.markAvailable(fileId)
        }
    }

    /** Feeds freshly generated cover bytes straight into the cache: a file that
     *  just finished uploading shows its cover on the next frame, with no list
     *  round-trip back to the server. No-op before register. */
    fun put(fileId: String, bytes: ByteArray) {
        val l = loader ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            l.put(fileId, bytes)
        }
    }

    suspend fun load(fileId: String): ImageBitmap? = loader?.load(fileId)
}

/**
 * In-memory thumbnail cache shared by all list/grid surfaces. Thumbnails are
 * small (<512px JPEG), so decoded bitmaps are bounded by a byte budget and
 * evicted LRU. Requests are deduplicated while in flight, and a failed fetch
 * (404 or a transient network error) is negatively cached so a missing cover
 * costs one request per interval, not one per recomposition. Negative entries
 * expire after [MISSING_TTL_MS] because the server may generate a cover a few
 * seconds after an upload lands; without expiry the new cover would stay
 * invisible until logout.
 */
class ThumbnailLoader(private val repo: FilesRepository) {
    /**
     * Ids whose cover became available while this process was running.
     *
     * The server generates covers lazily, so a tile that asked before the
     * cover existed has to be told when it appears. Polling for that was the
     * old answer: three 30s retries per visible row, restarted on every scroll
     * and off by as much as 15s from the 60s negative-cache TTL. This is the
     * same information as an event, for callers already collecting a
     * [kotlinx.coroutines.flow.StateFlow].
     *
     * The set only grows within a session, which is what a viewer needs (it
     * never has to unsubscribe) and what [clear] resets on account switch.
     */
    private val _readyIds = MutableStateFlow<Set<String>>(emptySet())
    val readyIds: StateFlow<Set<String>> = _readyIds.asStateFlow()
    // Insertion-ordered map: a cache hit re-inserts the key, so the first
    // entry is always the least recently used when evicting.
    private val cache = mutableMapOf<String, ImageBitmap>()
    private val cacheBytes = HashMap<String, Int>()
    private var totalBytes = 0
    private val inFlight = mutableMapOf<String, Deferred<ImageBitmap?>>()
    // fileId -> monotonic ms when the last failed fetch was recorded.
    private val missingAt = HashMap<String, Long>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Drops cached bitmaps, negative entries and in-flight fetches. Called on
     * logout / account switch so the previous account's covers never survive
     * in memory (in-flight fetches are cancelled outright).
     */
    suspend fun clear() {
        // Also resets the event set: after an account switch the new session
        // must not be told about covers that belonged to the previous one.
        _readyIds.value = emptySet()
        mutex.withLock {
            cache.clear()
            cacheBytes.clear()
            totalBytes = 0
            missingAt.clear()
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
        }
    }

    /**
     * Drops one file's bitmap and negative entry so the next [load] refetches.
     * Called on logout-level resets; for the upload-complete path see
     * [markAvailable] and [put], which keep whatever is already on screen.
     */
    suspend fun invalidate(fileId: String) {
        mutex.withLock {
            totalBytes -= cacheBytes.remove(fileId) ?: 0
            cache.remove(fileId)
            missingAt.remove(fileId)
            inFlight.remove(fileId)?.cancel()
        }
    }

    /**
     * Clears the negative entry so the next [load] retries the fetch, and tells
     * any tile waiting on this id to retry now rather than on its own timer.
     */
    suspend fun markAvailable(fileId: String) {
        _readyIds.update { it + fileId }
        mutex.withLock { missingAt.remove(fileId) }
    }

    /**
     * Inserts a freshly generated cover (the upload pipeline just made one)
     * without a network fetch. Decoding failure is silently dropped - the
     * tiles then fall back to the normal fetch path.
     */
    suspend fun put(fileId: String, bytes: ByteArray) {
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { bytes.decodeToImageBitmap() }.getOrNull()
        } ?: return
        insert(fileId, bitmap)
    }

    suspend fun load(fileId: String): ImageBitmap? {
        mutex.withLock {
            cache.remove(fileId)?.let { bmp ->
                cache[fileId] = bmp
                return bmp
            }
            missingAt[fileId]?.let { marked ->
                if (monotonicNowMs() - marked < MISSING_TTL_MS) return null
                // Expired: drop the negative mark so this call retries the fetch.
                missingAt.remove(fileId)
            }
        }
        val deferred = mutex.withLock {
            inFlight.getOrPut(fileId) { scope.async { fetch(fileId) } }
        }
        return deferred.await()
    }

    private suspend fun fetch(fileId: String): ImageBitmap? {
        try {
            val bytes = repo.thumbnailBytes(fileId).getOrNull()
            if (bytes == null) {
                mutex.withLock { missingAt[fileId] = monotonicNowMs() }
                return null
            }
            val bitmap = withContext(Dispatchers.Default) {
                runCatching { bytes.decodeToImageBitmap() }.getOrNull()
            }
            if (bitmap == null) {
                mutex.withLock { missingAt[fileId] = monotonicNowMs() }
                return null
            }
            insert(fileId, bitmap)
            return bitmap
        } finally {
            @Suppress("DeferredResultUnused")
            mutex.withLock {
                inFlight.remove(fileId)
            }
        }
    }

    /** Puts a decoded bitmap under [fileId] (LRU-bounded) and lifts any
     *  negative mark: the cover demonstrably exists now. */
    private suspend fun insert(fileId: String, bitmap: ImageBitmap) {
        val bytesCost = bitmap.width * bitmap.height * 4
        // Announced outside the lock: the flow update runs collectors, and a
        // collector that reloads must not run while this mutex is held.
        _readyIds.update { it + fileId }
        mutex.withLock {
            cache[fileId] = bitmap
            cacheBytes[fileId] = bytesCost
            totalBytes += bytesCost
            missingAt.remove(fileId)
            while (totalBytes > MAX_BYTES && cache.size > 1) {
                val eldestKey = cache.keys.first()
                totalBytes -= cacheBytes.remove(eldestKey) ?: 0
                cache.remove(eldestKey)
            }
        }
    }

    private companion object {
        /**
         * Budget for decoded bitmaps, in bytes. The web client shares one heap
         * with the Compose/wasm runtime and the tab's own JS objects, so it is
         * given a much smaller slice than the native clients: pinning 48 MB of
         * bitmaps in a browser tab is how the tab gets killed instead of the
         * cache simply staying small.
         */
        val MAX_BYTES = if (isWebPlatform()) 16 * 1024 * 1024 else 48 * 1024 * 1024

        /**
         * How long a failed thumbnail fetch stays negatively cached. Short by
         * design: the server generates covers lazily, so a fetch that missed
         * because the cover was still being made must retry soon, or a photo
         * uploaded seconds ago would keep its placeholder icon for minutes.
         */
        const val MISSING_TTL_MS = 60 * 1000L
    }
}
