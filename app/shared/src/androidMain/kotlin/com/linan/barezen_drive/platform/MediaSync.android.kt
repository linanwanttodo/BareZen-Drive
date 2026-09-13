package com.linan.barezen_drive.platform

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.linan.barezen_drive.AndroidContext
import com.linan.barezen_drive.data.local.AppPreferences
import com.linan.barezen_drive.data.local.TokenStorage
import com.linan.barezen_drive.sync.ReconcileWorker
import com.linan.barezen_drive.sync.SyncDb
import com.linan.barezen_drive.sync.SyncPolicy
import com.linan.barezen_drive.sync.SyncScan
import com.linan.barezen_drive.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

/**
 * Android album sync: a foreground WorkManager pass uploads new device media
 * through the normal upload pipeline (instant-upload dedup, resumable chunks),
 * driven by a MediaStore ContentObserver for a couple-of-seconds reaction to a
 * freshly taken photo and by periodic jobs for backstop coverage. The queue
 * itself lives in [SyncDb]; see feature notes 1-6 in the design.
 */
actual object MediaSync {

    private const val SYNC_WORK = "barezen-album-sync"
    private const val NOW_WORK = "barezen-album-sync-now"
    private const val TRIGGER_WORK = "barezen-album-sync-trigger"
    private const val RECONCILE_WORK = "barezen-album-reconcile"
    private const val META_PREFS = "barezen_sync_meta"
    private const val KEY_LAST_SYNC = "lastSyncAt"
    private const val KEY_LAST_SCAN = "lastScanAt"

    /**
     * Linear 10-minute backoff. WorkManager's default is exponential starting at
     * 30 s, which spends the first minutes of an outage hammering a dead link.
     */
    private const val BACKOFF_MINUTES = 10L

    /** Input flag: this pass only needs to look at media changed since the last
     *  scan. Set by the change observer, which knows one new photo landed. */
    const val KEY_INCREMENTAL = "incremental"

    /** Input flag: the user pressed "sync now", so the pass runs even where the
     *  stored policy would otherwise hold it back. */
    const val KEY_MANUAL = "manual"

    actual val supported: Boolean = true

    private val _status = MutableStateFlow(BackupStatus())
    actual val status: StateFlow<BackupStatus> = _status

    private val prefs get() = AndroidContext.app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)

    actual fun apply(enabled: Boolean, wifiOnly: Boolean, chargingOnly: Boolean) {
        val wm = WorkManager.getInstance(AndroidContext.app)
        if (!enabled) {
            wm.cancelUniqueWork(SYNC_WORK)
            wm.cancelUniqueWork(RECONCILE_WORK)
            wm.cancelUniqueWork(TRIGGER_WORK)
            MediaObserverBridge.unregister()
            refreshStatus()
            return
        }
        // The periodic pass can carry the whole policy, so WorkManager only wakes
        // it when every condition holds - no battery is spent discovering that the
        // phone sits at 4 % and unplugged.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(chargingOnly)
            .setRequiresBatteryNotLow(true)
            .build()
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(SYNC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        // A once-a-day reconcile catches events the observer missed (killed
        // process, rapid deletes) and prunes rows for files removed from the
        // device. It only touches the queue and never uploads, so it keeps a plain
        // CONNECTED constraint - holding it behind the charging rule would let the
        // queue drift forever on a phone that is never plugged in overnight.
        val reconcile = PeriodicWorkRequestBuilder<ReconcileWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(RECONCILE_WORK, ExistingPeriodicWorkPolicy.UPDATE, reconcile)
        MediaObserverBridge.register(AndroidContext.app, wifiOnly)
        refreshStatus()
    }

    actual fun syncNow(wifiOnly: Boolean) {
        // An explicit "sync now" always runs: queueing it behind the Wi-Fi or
        // charging rule made the button a silent no-op on metered connections.
        // KEY_MANUAL tells the worker this is a deliberate override, so it skips
        // the policy re-check the change-triggered path relies on.
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(KEY_MANUAL to true))
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(AndroidContext.app)
            .enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    actual fun stop() {
        // Kill the one-shot entries (manual sync-now, content-change trigger).
        // The periodic schedule stays: apply() owns it, and "stop syncing" must
        // not silently un-book the next scheduled tick.
        runCatching {
            val wm = WorkManager.getInstance(AndroidContext.app)
            wm.cancelUniqueWork(NOW_WORK)
            wm.cancelUniqueWork(TRIGGER_WORK)
        }
        // Ask running batches to wind down: the in-flight file completes, the
        // rest of the queue keeps its PENDING rows for the next trigger.
        com.linan.barezen_drive.data.transfer.TransferCenter.cancelSyncBatches()
        refreshStatus()
    }

    actual suspend fun listBuckets(): List<BackupBucket> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Scan first so the screen reflects albums taken after the last
            // worker pass, then read the queue's per-album rollup. The scan is
            // deliberately allowed to throw: a SecurityException (media read
            // permission denied) returning an empty list would be
            // indistinguishable from "this device has no albums", and the
            // one-shot first-run review would be burned by a dialog that had
            // nothing to show.
            SyncDb.upsertScanned(SyncScan.scanAll(AndroidContext.app))
            SyncDb.listBuckets().map { BackupBucket(it.bucket, it.total, it.included) }
        }

    actual fun setBucketIncluded(bucket: String, included: Boolean) {
        SyncDb.setBucketIncluded(bucket, included)
        refreshStatus()
        // Excluding an album leaves its rows queued but skipped; including one
        // makes them eligible again, and nothing else would pick them up until the
        // next observer event or periodic tick - so the queue is re-driven here.
        // KEEP, so an in-flight manual sync is not clobbered.
        if (included) {
            WorkManager.getInstance(AndroidContext.app).enqueueUniqueWork(
                NOW_WORK,
                ExistingWorkPolicy.KEEP,
                changeTriggeredRequest(wifiOnly()),
            )
        }
    }

    /** Recompute the status card from the queue + device state; safe from any thread. */
    fun refreshStatus() {
        val app = runCatching { AndroidContext.app }.getOrNull() ?: return
        val ap = runCatching { AppPreferences.get() }.getOrNull()
        val enabled = ap?.albumAutoSync ?: false
        val pending = runCatching { SyncDb.dueCount() }.getOrDefault(0)
        val active = runCatching { SyncDb.uploadingCount() }.getOrDefault(0)
        _status.value = BackupStatus(
            enabled = enabled,
            pending = pending,
            active = active,
            failed = runCatching { SyncDb.failedCount() }.getOrDefault(0),
            excludedBuckets = runCatching { SyncDb.excludedBucketCount() }.getOrDefault(0),
            lastSyncAt = app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SYNC, 0L),
            // Only claim a pause when work is waiting and nothing is moving: an
            // empty queue on a plane is finished rather than paused, and a pass
            // that is currently running is not paused either.
            pausedReason = if (enabled && pending > 0 && active == 0) {
                backupPauseReason(
                    // A pass with no session returns early without touching the
                    // queue, so without this the card would blame the network.
                    signedIn = TokenStorage.accessToken != null,
                    hasUsableNetwork = DeviceState.hasUsableNetwork(ap?.syncWifiOnly ?: true),
                    needsCharging = ap?.albumSyncChargingOnly ?: false,
                    isCharging = DeviceState.isCharging(),
                    needsBatteryNotLow = true,
                    isBatteryLow = DeviceState.isBatteryLow(),
                )
            } else {
                null
            },
        )
    }

    /**
     * Upload lanes for the link currently in use: [SyncPolicy.LANES_UNMETERED] on
     * Wi-Fi, [SyncPolicy.LANES_METERED] on mobile data. Read when the pass starts
     * rather than baked into the work request, because a pass can outlive a
     * network switch and the live reading is the one that matters.
     */
    internal fun syncLanes(): Int = SyncPolicy.lanesFor(DeviceState.isUnmetered())

    /**
     * Whether the stored policy lets an upload pass run right now.
     *
     * The request that wakes the change-triggered pass cannot express the
     * charging / battery floor: WorkManager rejects such a combination with
     * "Expedited jobs only support network and storage constraints". So that
     * request carries the network constraint alone and the worker re-checks the
     * rest here, which keeps the user's policy authoritative instead of silently
     * uploading on battery just because a photo landed.
     */
    internal fun policyAllowsPass(): Boolean {
        val ap = runCatching { AppPreferences.get() }.getOrNull() ?: return true
        if (ap.albumSyncChargingOnly && !DeviceState.isCharging()) return false
        return !DeviceState.isBatteryLow()
    }

    fun markSynced() {
        runCatching { prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply() }
    }

    /** Finish time of the last MediaStore read, so the next change-triggered pass
     *  can scan incrementally instead of walking the whole library. Zero (never
     *  scanned) forces a full scan. */
    internal fun lastScanAt(): Long = runCatching { prefs.getLong(KEY_LAST_SCAN, 0L) }.getOrDefault(0L)

    internal fun markScanned() {
        runCatching { prefs.edit().putLong(KEY_LAST_SCAN, System.currentTimeMillis()).apply() }
    }

    internal fun wifiOnly(): Boolean =
        runCatching { AppPreferences.get().syncWifiOnly }.getOrDefault(true)

    /**
     * The request used by the change observer and by the album picker: expedited
     * so a freshly taken photo does not wait for the six-hour tick, capped at the
     * network constraint because that is all an expedited request may carry.
     */
    private fun changeTriggeredRequest(wifiOnly: Boolean) =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(KEY_INCREMENTAL to true))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

    /** Called by the observer bridge, which owns the debounce timer. */
    internal fun enqueueChangeTriggered(wifiOnly: Boolean) {
        runCatching {
            WorkManager.getInstance(AndroidContext.app)
                .enqueueUniqueWork(TRIGGER_WORK, ExistingWorkPolicy.REPLACE, changeTriggeredRequest(wifiOnly))
        }
        refreshStatus()
    }
}

/**
 * Device power and connectivity readings for the status card. Every reading
 * degrades to "the constraint is satisfied" on failure: a missing value must
 * never invent a pause reason and make the card lie about why nothing runs.
 */
private object DeviceState {

    fun hasUsableNetwork(wifiOnly: Boolean): Boolean {
        val ctx = runCatching { AndroidContext.app }.getOrNull() ?: return true
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        // VALIDATED means the link actually reaches the internet, not just a
        // captive portal or a router with no uplink.
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        return !wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * True when the active link is not metered. Unknown readings are reported as
     * metered: the opposite default would silently spend the user's mobile data
     * on a second concurrent chunk stream, and that is the worse mistake.
     */
    fun isUnmetered(): Boolean {
        val ctx = runCatching { AndroidContext.app }.getOrNull() ?: return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun isCharging(): Boolean {
        val i = batteryIntent() ?: return true
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) {
            return true
        }
        // Some chargers report NOT_CHARGING while still plugged in.
        return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    fun isBatteryLow(): Boolean {
        val i = batteryIntent() ?: return false
        // EXTRA_BATTERY_LOW is the flag JobScheduler's battery-not-low constraint
        // reads. Battery saver counts too: a phone in saver mode behaves as if it
        // were low and the platform would refuse the job anyway.
        if (i.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false)) return true
        val pm = runCatching { AndroidContext.app.getSystemService(Context.POWER_SERVICE) as? PowerManager }
            .getOrNull()
        return pm?.isPowerSaveMode == true
    }

    /** The sticky battery broadcast, read through a null receiver so nothing is
     *  registered and nothing has to be unregistered later. */
    private fun batteryIntent(): Intent? = runCatching {
        AndroidContext.app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
}

/**
 * Debounced bridge from MediaStore change notifications to an expedited sync.
 * Held as a singleton so [MediaSync.apply] can (un)register exactly one observer.
 */
internal object MediaObserverBridge {
    private const val DEBOUNCE_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var wifiOnly: Boolean = true

    fun register(context: Context, wifiOnly: Boolean) {
        this.wifiOnly = wifiOnly
        if (observer != null) return
        val cr = context.contentResolver
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = scheduleSync()
        }
        // Descendants = watch every album, not just a single collection root.
        cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs)
        cr.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, obs)
        observer = obs
    }

    fun unregister() {
        val obs = observer ?: return
        runCatching { AndroidContext.app.contentResolver.unregisterContentObserver(obs) }
        handler.removeCallbacksAndMessages(null)
        observer = null
    }

    /** Coalesce a burst of MediaStore writes (a burst of screenshots, a camera
     *  save that touches image+video rows) into one run 5 s after the last event. */
    private fun scheduleSync() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ MediaSync.enqueueChangeTriggered(wifiOnly) }, DEBOUNCE_MS)
    }
}
