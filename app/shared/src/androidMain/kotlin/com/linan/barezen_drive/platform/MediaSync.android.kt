package com.linan.barezen_drive.platform

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.linan.barezen_drive.AndroidContext
import com.linan.barezen_drive.data.api.ApiClient
import com.linan.barezen_drive.data.repo.AlbumFolder
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.data.transfer.TransferCenter
import com.linan.barezen_drive.data.transfer.TransferKind
import com.linan.barezen_drive.data.upload.UploadManager
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * Android album sync: a periodic WorkManager job scans MediaStore for media
 * that has not been uploaded yet and pushes it through the normal upload
 * pipeline (so instant-upload dedup applies and no bytes are sent twice).
 *
 * Already-synced items are remembered by (content Uri, date-modified) in
 * preferences; the date component means an edited photo syncs again, while an
 * untouched one is skipped forever.
 */
actual object MediaSync {

    private const val WORK_NAME = "barezen-album-sync"
    private const val PREFS = "barezen_sync"
    private const val KEY_SYNCED = "synced"

    actual val supported: Boolean = true

    actual fun apply(enabled: Boolean, wifiOnly: Boolean) {
        val wm = WorkManager.getInstance(AndroidContext.app)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    actual fun syncNow(wifiOnly: Boolean) {
    // An explicit "sync now" always runs: queueing it behind the Wi-Fi
    // constraint made the button a silent no-op on metered connections.
    val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
    WorkManager.getInstance(AndroidContext.app)
        .enqueueUniqueWork(WORK_NAME + "-now", ExistingWorkPolicy.REPLACE, request)
}

    internal fun syncedSet(): MutableSet<String> =
        AndroidContext.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SYNCED, emptySet())!!.toMutableSet()

    internal fun saveSynced(set: Set<String>) {
        AndroidContext.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_SYNCED, set).apply()
    }
}

/** One sync pass: find unsynced media, upload it, remember the successes. */
class SyncWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result = runBlocking {
        // WorkManager may start this worker in a fresh process where
        // MainActivity never ran; AndroidContext must be initialized first.
        runCatching { AndroidContext.init(applicationContext) }
        val store = com.linan.barezen_drive.data.local.TokenStorage
        // Nothing to sync against until the user has signed in somewhere.
        if (store.baseUrl.isBlank() || store.accessToken == null) return@runBlocking Result.success()

        val repo = FilesRepository(ApiClient())
        val uploader = UploadManager(repo)
        val deviceFolder = AlbumFolder.resolve(repo, deviceName(), legacyDeviceName()) ?: return@runBlocking Result.retry()
        val synced = MediaSync.syncedSet()
        val ctx = applicationContext

        // One aggregate transfer for the whole pass, Google-Photos style: a
        // single progress row and a single notification that counts files up,
        // instead of one entry (and one shade entry) per photo.
        val batchTitle = if (java.util.Locale.getDefault().language == "zh") "相册同步" else "Album sync"
        val pending = queryMedia(ctx)
            .map { it to "${it.uri}|${it.dateModified}" }
            .filter { (_, fingerprint) -> fingerprint !in synced }
        if (pending.isEmpty()) {
            MediaSync.saveSynced(synced)
            return@runBlocking Result.success()
        }
        val total = pending.size
        val batchId = TransferCenter.start(batchTitle, TransferKind.SYNC, total.toLong())

        var doneCount = 0
        var failed = 0
        for ((item, fingerprint) in pending) {
            // The stop button marks the batch cancelled between files; the
            // in-flight upload finishes, the rest of the queue is dropped.
            if (TransferCenter.isCancelled(batchId)) {
                TransferCenter.fail(batchId, if (java.util.Locale.getDefault().language == "zh") "已停止" else "stopped")
                MediaSync.saveSynced(synced)
                return@runBlocking Result.success()
            }
            try {
                val picked = AndroidPickedFile(ctx, item.uri)
                // Category folder created on demand from the phone-album name.
                val target = item.album?.takeIf { it.isNotBlank() }
                    ?.let { AlbumFolder.resolveCategory(repo, deviceFolder, it) }
                    ?: deviceFolder
                // The batch owns the transfer entry; per-file reporting is off.
                uploader.upload(picked, target, reportTransfer = false)
                    .onSuccess { synced.add(fingerprint) }
                    .onFailure { failed++ }
            } catch (e: kotlinx.coroutines.CancellationException) {
                TransferCenter.fail(batchId, e.message ?: "cancelled")
                throw e
            } catch (e: Exception) {
                failed++
            }
            doneCount++
            TransferCenter.progress(batchId, doneCount.toLong(), total.toLong(), "$doneCount / $total")
        }
        MediaSync.saveSynced(synced)
        if (failed == 0) {
            TransferCenter.done(batchId)
        } else {
            TransferCenter.fail(batchId, if (java.util.Locale.getDefault().language == "zh") "$failed 张失败，已同步 ${total - failed} 张" else "$failed failed, ${total - failed} synced")
        }
        Result.success()
    }

    private data class MediaRef(
        val uri: Uri,
        val album: String?,
        val dateModified: Long,
    )

    private fun queryMedia(ctx: Context): List<MediaRef> {
        val out = mutableListOf<MediaRef>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        for (collection in collections) {
            ctx.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val iId = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val iPath = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val iBucket = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val iDate = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val album = (if (iPath >= 0) c.getString(iPath)?.trim('/')?.split('/')?.lastOrNull() else null)
                        ?: (if (iBucket >= 0) c.getString(iBucket) else null)
                    val date = if (iDate >= 0) c.getLong(iDate) else 0L
                    // Hidden cache albums (dot-names, long hex hashes like
                    // WeChat's) would otherwise become garbage category folders.
                    val cleanAlbum = album?.takeIf {
                        it.isNotBlank() && !it.startsWith(".") &&
                            !Regex("[0-9a-fA-F]{16,}").matches(it)
                    }
                    out.add(MediaRef(uri, cleanAlbum, date))
                }
            }
        }
        return out
    }
}
