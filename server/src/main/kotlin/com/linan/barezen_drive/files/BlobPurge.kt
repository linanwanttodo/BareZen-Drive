package com.linan.barezen_drive.files

import com.linan.barezen_drive.storage.StorageProvider
import com.linan.barezen_drive.storage.thumbKey
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("BlobPurge")

/**
 * Physical removal of blob keys that lost their last database reference, plus
 * their content-addressed covers (a cover is keyed by the blob's sha256, so it
 * becomes unreachable together with the bytes).
 *
 * Callers must only hand over keys after the transaction that dropped the last
 * reference committed. A failing unlink is logged and skipped: the bytes are
 * then unreachable garbage, which is a disk-space problem, not a correctness
 * one - and it must never turn a completed delete request into a 500.
 */
suspend fun deleteStoredBlobs(storage: StorageProvider, keys: List<String>) {
    keys.forEach { key ->
        runCatching {
            storage.delete(key)
            storage.delete(thumbKey(key.substringAfterLast('/')))
        }.onFailure { log.warn("blob cleanup failed for {}", key, it) }
    }
}
