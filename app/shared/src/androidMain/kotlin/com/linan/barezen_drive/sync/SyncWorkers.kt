package com.linan.barezen_drive.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.linan.barezen_drive.AndroidContext
import com.linan.barezen_drive.data.local.TokenStorage
import com.linan.barezen_drive.platform.MediaSync

/**
 * Foreground notification used by [SyncWorker] while it uploads, so the backup
 * survives process death on recent Android (a plain background worker is killed
 * a couple of minutes after the screen turns off on OEM skins).
 */
internal object BackupNotification {
    const val ID = 4711
    const val CHANNEL = "album_backup"

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = AndroidContext.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Album backup", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Automatic photo and video backup progress" },
            )
        }
    }

    fun build(done: Int, total: Int): Notification {
        ensureChannel()
        val title = if (java.util.Locale.getDefault().language == "zh") "正在备份相册" else "Backing up photos"
        val text = if (total > 0) "$done / $total" else null
        val b = NotificationCompat.Builder(AndroidContext.app, CHANNEL)
            .setContentTitle(title)
            .apply { text?.let { setContentText(it) } }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, if (total > 0) (done * 100 / total) else 0, total == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        // The same app-icon silhouette every other notification draws. This
        // used to point at a VectorDrawable resource, which the shade cannot
        // always inflate - it then falls back to the platform's own drawing,
        // and that is the stock figure users kept seeing mid-backup.
        com.linan.barezen_drive.platform.TransferNotifier.smallIcon()?.let { b.setSmallIcon(it) }
        return b.build()
    }

    fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val n = build(done, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ID, n)
        }
    }
}

/**
 * One upload pass: reconcile the queue against MediaStore, then drain the
 * pending rows through [SyncUploadQueue] with a foreground notification.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    /**
     * WorkManager itself calls this on the expedited path (below Android 12 an
     * expedited job is emulated with a foreground service, and the platform asks
     * the worker for its notification). The CoroutineWorker default throws, which
     * would kill the pass before it started, so it has to be overridden even
     * though [doWork] also calls setForeground() for the live progress.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = BackupNotification.foregroundInfo(0, 0)

    override suspend fun doWork(): Result {
        runCatching { AndroidContext.init(applicationContext) }
        // Nothing to sync against until the user has signed in somewhere.
        if (TokenStorage.baseUrl.isBlank() || TokenStorage.accessToken == null) return Result.success()

        // A user-initiated pass ignores the charging / battery policy; an
        // automatic one honours it. See MediaSync.policyAllowsPass() for why the
        // check lives here rather than in the work request's constraints.
        val manual = inputData.getBoolean(MediaSync.KEY_MANUAL, false)
        if (!manual && !MediaSync.policyAllowsPass()) {
            // Not a failure: the periodic job carries the full policy and will
            // pick the queue up as soon as it is allowed to run.
            MediaSync.refreshStatus()
            return Result.success()
        }

        SyncDb.migrateLegacyOnce()
        SyncDb.resetUploadingToPending()
        setForeground(BackupNotification.foregroundInfo(0, 0))

        // The change observer asks for an incremental read: only media whose
        // DATE_MODIFIED moved past the last scan's watermark is re-read, so a
        // photo landing no longer walks the whole library. The periodic tick and
        // the reconcile job still pass null and take everything.
        val incremental = inputData.getBoolean(MediaSync.KEY_INCREMENTAL, false)
        val since = if (incremental) SyncPolicy.scanSinceSeconds(MediaSync.lastScanAt()) else null
        val scanned = runCatching { SyncScan.scanAll(applicationContext, since) }.getOrNull()
        if (scanned != null) {
            SyncDb.upsertScanned(scanned)
            MediaSync.markScanned()
        }

        val totalEstimate = SyncDb.dueCount()
        val pass = runCatching {
            SyncUploadQueue.runPass(MediaSync.syncLanes()) { done, total ->
                val grand = total.takeIf { it > 0 } ?: totalEstimate
                runCatching { setForeground(BackupNotification.foregroundInfo(done, grand)) }
                // Keep the transfer-centre card live (pending / uploading /
                // paused) without a second source of truth: it reads the queue.
                MediaSync.refreshStatus()
            }
        }.getOrNull()

        if (pass != null && !pass.stoppedEarly && pass.failed == 0 && SyncDb.dueCount() == 0) {
            MediaSync.markSynced()
        }
        MediaSync.refreshStatus()

        // Work left in the queue (failures with retries remaining, or an early
        // stop from a dropped connection) is retried with the linear backoff the
        // request carries; retry() keeps us from hot-looping against an offline
        // server, which is what the default 30 s exponential backoff used to do.
        val remaining = SyncDb.dueCount()
        return if (remaining > 0 && (pass == null || pass.stoppedEarly || pass.failed > 0)) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}

/**
 * Daily full reconciliation (feature 2's backstop). ContentObserver events can
 * be missed (process killed, rapid toggles), so once a day the queue is rebuilt
 * from a complete scan and rows whose Uri no longer exists are pruned. This is
 * also the only reader that may prune: an incremental scan cannot tell "deleted"
 * from "unchanged", so pruning there would wipe the whole queue.
 */
class ReconcileWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching { AndroidContext.init(applicationContext) }
        if (TokenStorage.baseUrl.isBlank() || TokenStorage.accessToken == null) return Result.success()
        SyncDb.migrateLegacyOnce()
        val scanned = runCatching { SyncScan.scanAll(applicationContext) }.getOrNull() ?: return Result.retry()
        SyncDb.upsertScanned(scanned)
        SyncDb.pruneMissing(SyncScan.presentUris(scanned))
        MediaSync.markScanned()
        MediaSync.refreshStatus()
        return Result.success()
    }
}
