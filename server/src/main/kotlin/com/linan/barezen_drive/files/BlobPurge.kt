package com.linan.barezen_drive.files

import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.storage.StorageProvider
import com.linan.barezen_drive.storage.thumbKey
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("BlobPurge")

/**
 * Physical removal of blob keys that lost their last database reference, plus
 * their content-addressed covers (a cover is keyed by the blob's sha256, so it
 * becomes unreachable together with the bytes).
 *
 * Callers must only hand over keys after the transaction that dropped the last
 * reference committed. Every caller passes through this one choke point, so
 * the unlink is guarded here with a final reference recount in a fresh
 * transaction: the purge decision was made earlier, and a dedup upload that
 * passed its storage.exists() probe in between (its reference row commits
 * milliseconds later) must not lose the blob it is about to reference. The
 * residual race window is the span between this recount's commit and the
 * unlink itself - microseconds, versus the whole check-then-insert span of a
 * concurrent upload without the guard. A key that gains a reference again is
 * simply skipped; a failing unlink is logged and skipped: the bytes are then
 * unreachable garbage, which is a disk-space problem, not a correctness one -
 * and it must never turn a completed delete request into a 500.
 */
suspend fun deleteStoredBlobs(storage: StorageProvider, keys: List<String>) {
    if (keys.isEmpty()) return
    val stillOrphan = transaction(DatabaseFactory.db) { FileService.orphanBlobKeys(keys) }
    stillOrphan.forEach { key ->
        runCatching {
            storage.delete(key)
            storage.delete(thumbKey(key.substringAfterLast('/')))
        }.onFailure { log.warn("blob cleanup failed for {}", key, it) }
    }
}
