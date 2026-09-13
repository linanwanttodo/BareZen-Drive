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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
        mutex.withLock {
            cache.clear()
            cacheBytes.clear()
            totalBytes = 0
            missingAt.clear()
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
        }
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
            val bytesCost = bitmap.width * bitmap.height * 4
            mutex.withLock {
                cache[fileId] = bitmap
                cacheBytes[fileId] = bytesCost
                totalBytes += bytesCost
                while (totalBytes > MAX_BYTES && cache.size > 1) {
                    val eldestKey = cache.keys.first()
                    totalBytes -= cacheBytes.remove(eldestKey) ?: 0
                    cache.remove(eldestKey)
                }
            }
            return bitmap
        } finally {
            mutex.withLock {
                inFlight.remove(fileId)
                Unit
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

        /** How long a failed thumbnail fetch stays negatively cached. */
        const val MISSING_TTL_MS = 5 * 60 * 1000L
    }
}
