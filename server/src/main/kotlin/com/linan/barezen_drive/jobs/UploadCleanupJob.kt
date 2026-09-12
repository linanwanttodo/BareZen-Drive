package com.linan.barezen_drive.jobs

import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.files.UploadService
import com.linan.barezen_drive.storage.StorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

object UploadCleanupJob {
    private val log = LoggerFactory.getLogger(UploadCleanupJob::class.java)

    fun start(scope: CoroutineScope, storage: StorageProvider): Job = scope.launch {
        while (isActive) {
            // The loop starts before module() connects the database; poll briefly
            // instead of skipping the tick and waiting 6h with dead sessions around.
            if (!DatabaseFactory.connected) {
                delay(5.seconds)
                continue
            }
            runCatching { UploadService.cleanupExpired(storage) }
                .onFailure { log.error("upload session cleanup failed", it) }
            delay(6.hours)
        }
    }
}
