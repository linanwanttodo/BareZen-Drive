package com.linan.barezen_drive

import android.app.Application
import com.linan.barezen_drive.data.local.AppPreferences
import com.linan.barezen_drive.platform.MediaSync
import com.linan.barezen_drive.platform.TransferNotifier

/**
 * Process-wide entry that must run even when the app is cold-started by a
 * background WorkManager job (no Activity). It seeds [AndroidContext] so the
 * sync subsystem's singletons resolve, restores the notification icon, and
 * re-applies the saved auto-sync policy so the ContentObserver and the
 * periodic/reconcile jobs are registered in a worker-only process too.
 */
class BareZenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContext.init(this)
        runCatching { TransferNotifier.setSmallIcon(R.drawable.ic_notification) }
        runCatching {
            val prefs = AppPreferences.get()
            MediaSync.apply(prefs.albumAutoSync, prefs.syncWifiOnly, prefs.albumSyncChargingOnly)
        }
    }
}
