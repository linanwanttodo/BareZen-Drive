package com.linan.barezen_drive.files

import com.linan.barezen_drive.db.BlobDeleteQueueTable
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.storage.StorageProvider
import com.linan.barezen_drive.storage.thumbKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("BlobPurge")

/**
 * Grace period before a blob's bytes are unlinked.
 *
 * Long enough that a dedup upload racing the delete has committed its
 * reference long before the sweep looks again, short enough that "delete a
 * 5 GB file" does not leave 5 GB on disk for a day. The advisory lock is what
 * actually makes the race impossible; this is the belt to its braces, and it
 * is also what makes the delete *re-decidable* - a sweep that finds a new
 * reference leaves the blob alone.
 *
 * Freed bytes therefore appear on disk up to this plus one cleanup cycle
 * (6h) after the delete: about 8 hours worst case, not 2. A delete that must
 * return the space now has to wait for the sweep, which is the deliberate
 * trade - the alternative is a window where a live file can lose its content.
 */
const val BLOB_GRACE_MS = 2 * 60 * 60 * 1000L

/**
 * Queues blobs that lost their last database reference. Nothing is unlinked
 * here any more - see [processQueue] for why.
 *
 * Callers must only hand over keys after the transaction that dropped the last
 * reference committed. Every caller passes through this one choke point, so the
 * recount lives here too: a key that still has a reference (a version snapshot,
 * a delete that was rolled back, a dedup upload that landed in between) never
 * reaches the queue.
 */
suspend fun deleteStoredBlobs(storage: StorageProvider, keys: List<String>) {
    if (keys.isEmpty()) return
    // The recount is a blocking JDBC transaction, so the hop lives here rather
    // than at each of the nine call sites: a caller that forgets the wrap parks
    // the request thread (or, for the event-loop callers, the thread every
    // other request needs).
    withContext(Dispatchers.IO) {
        val stillOrphan = transaction(DatabaseFactory.db) { FileService.orphanBlobKeys(keys) }
        if (stillOrphan.isEmpty()) return@withContext
        runCatching { transaction(DatabaseFactory.db) { enqueue(stillOrphan) } }
            .onFailure { log.warn("could not queue {} blobs for deletion", stillOrphan.size, it) }
    }
}

/**
 * Puts keys on the delete queue, dropping any that came back to life.
 *
 * [now] is only written when the key is not already queued, so a delete path
 * that runs twice cannot restart the grace period and leak the blob. One
 * recount for the whole batch, not one per key: this runs on the request path
 * of every delete, and a version cascade can hand it dozens of keys.
 */
internal fun enqueue(keys: List<String>, now: Long = System.currentTimeMillis()) {
    if (keys.isEmpty()) return
    val unique = keys.distinct()
    val orphans = FileService.orphanBlobKeys(unique).toSet()
    val revived = unique.filter { it !in orphans }
    if (revived.isNotEmpty()) BlobDeleteQueueTable.deleteWhere { BlobDeleteQueueTable.storageKey inList revived }
    unique.filter { it in orphans }.forEach { key ->
        BlobDeleteQueueTable.insertIgnore {
            it[storageKey] = key
            it[queuedAt] = now
        }
    }
}

/**
 * Second half of the delete: unlink the keys whose grace period is up, unless
 * something referenced them again in the meantime.
 *
 * The recount, the unlink and the dequeue run per key inside one transaction
 * that holds the same per-key PostgreSQL advisory lock the upload side takes
 * (in UploadService). Recounting in a transaction of its own would release the
 * lock exactly where the sweep is most vulnerable - a dedup upload committing
 * in between, its new row pointing at bytes the unlink is about to remove.
 * Serialized this way the window is closed rather than merely narrowed. On H2
 * the lock is a no-op: the tests cover the queue, the grace window and the
 * recount, but not the mutual exclusion itself.
 *
 * A failing unlink is logged and the key stays queued for the next sweep: the
 * bytes are then unreachable garbage, which is a disk-space problem, not a
 * correctness one, and it must never turn a completed delete into a 500.
 *
 * Returns how many blobs this pass actually unlinked.
 */
suspend fun processQueue(
    storage: StorageProvider,
    graceMs: Long = BLOB_GRACE_MS,
    now: Long = System.currentTimeMillis(),
): Int {
    val due = withContext(Dispatchers.IO) {
        transaction(DatabaseFactory.db) {
            BlobDeleteQueueTable.selectAll()
                .where { BlobDeleteQueueTable.queuedAt lessEq (now - graceMs) }
                .map { it[BlobDeleteQueueTable.storageKey] }
        }
    }
    if (due.isEmpty()) return 0
    var unlinked = 0
    withContext(Dispatchers.IO) {
        for (key in due) {
            // Everything for this key happens inside one lock *and* one
            // transaction: recount, unlink, dequeue. The lock is
            // transaction-scoped, so a recount that committed before the
            // unlink would have released it and left the exact window this
            // whole design exists to close.
            // The inner transaction joins the lock's own (same database, same
            // thread), so the dequeue commits with the unlink and the lock is
            // still held when the work is done. On H2 the lock opens no
            // transaction and the inner one is simply the only one.
            val removed = BlobPurgeLock.withKey(key) {
                transaction(DatabaseFactory.db) {
                    if (FileService.orphanBlobKeys(listOf(key)).isEmpty()) {
                        // Referenced again - most likely a dedup upload that
                        // landed inside the window. Off the queue, so later
                        // sweeps stop re-checking a live blob.
                        dequeue(listOf(key))
                        return@transaction false
                    }
                    val ok = runCatching {
                        // Two deletes, not one transaction with them: an
                        // unreachable blob left behind is a disk-space problem,
                        // and it must never turn a completed delete into a 500.
                        // The IO is one unlink locally and one DELETE on S3, so
                        // the lock (and this transaction) is held for a
                        // comparable moment to the upload side.
                        runBlocking { storage.delete(key) }
                        runBlocking { storage.delete(thumbKey(key.substringAfterLast('/'))) }
                    }.onFailure { log.warn("blob cleanup failed for {}", key, it) }.isSuccess
                    // A failed unlink keeps its queue entry on purpose: the
                    // next sweep retries it, whereas dropping it here would
                    // leak the bytes forever.
                    if (ok) dequeue(listOf(key))
                    ok
                }
            }
            if (removed) unlinked++
        }
    }
    return unlinked
}

internal fun dequeue(keys: List<String>) {
    if (keys.isEmpty()) return
    BlobDeleteQueueTable.deleteWhere { BlobDeleteQueueTable.storageKey inList keys.distinct() }
}
