package com.linan.barezen_drive

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat
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
        runCatching { installNotificationIcons() }
        runCatching {
            val prefs = AppPreferences.get()
            MediaSync.apply(prefs.albumAutoSync, prefs.syncWifiOnly, prefs.albumSyncChargingOnly)
        }
    }

    /**
     * Both notification icon slots carry the app icon itself, scaled down -
     * the full launcher artwork, for the small icon just as for the large
     * one. No silhouette, no layer extraction, no recolouring: the mark the
     * user sees must be the mark on the home screen. Rendered once to a
     * bitmap at startup, because a bitmap always inflates in the shade -
     * which is exactly what the per-notification vector resource did not
     * (that failure is what once made the shade fall back to its own stock
     * figure).
     *
     * One platform fact recorded here so nobody reads it as a bug later: the
     * status bar flattens whatever an app hands it to a single tint taken
     * from the icon's alpha channel. That is Android's rule for every app up
     * there and not something this code decides; the shade shows the icon as
     * delivered.
     */
    private fun installNotificationIcons() {
        val size = 128
        val d = ContextCompat.getDrawable(this, R.mipmap.ic_launcher) ?: return
        val icon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(icon)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        TransferNotifier.setAppIcons(
            androidx.core.graphics.drawable.IconCompat.createWithBitmap(icon),
            icon,
        )
    }
}
