package com.linan.barezen_drive.ui.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.linan.barezen_drive.data.repo.FilesRepository
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
 * evicted LRU. Requests are deduplicated while in flight, and a 404 (no
 * cover on the server) is negatively cached so a missing cover costs one
 * request per session, not one per recomposition.
 */
class ThumbnailLoader(private val repo: FilesRepository) {
    // Insertion-ordered map: a cache hit re-inserts the key, so the first
    // entry is always the least recently used when evicting.
    private val cache = mutableMapOf<String, ImageBitmap>()
    private val cacheBytes = HashMap<String, Int>()
    private var totalBytes = 0
    private val inFlight = mutableMapOf<String, Deferred<ImageBitmap?>>()
    private val missing = mutableSetOf<String>()
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
            missing.clear()
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
            if (fileId in missing) return null
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
                mutex.withLock { missing.add(fileId) }
                return null
            }
            val bitmap = withContext(Dispatchers.Default) {
                runCatching { bytes.decodeToImageBitmap() }.getOrNull()
            }
            if (bitmap == null) {
                mutex.withLock { missing.add(fileId) }
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
        const val MAX_BYTES = 48 * 1024 * 1024
    }
}
