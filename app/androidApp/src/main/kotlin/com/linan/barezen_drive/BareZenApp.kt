package com.linan.barezen_drive

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
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
        runCatching { TransferNotifier.setSmallIcon(R.drawable.ic_notification) }
        runCatching { prepareNotificationIcons() }
        runCatching {
            val prefs = AppPreferences.get()
            MediaSync.apply(prefs.albumAutoSync, prefs.syncWifiOnly, prefs.albumSyncChargingOnly)
        }
    }

    /**
     * The shade wants the app icon on a notification, and Android renders it
     * in two pieces: the small icon is drawn as a flat tint taken only from
     * the bitmap's alpha channel (so the coloured artwork would read as a
     * solid block there - it must be a white silhouette), while the large
     * icon keeps the full colour. Both are derived from the launcher icon:
     * the silhouette from the adaptive icon's FOREGROUND layer, because the
     * background is opaque and would silhouette into a plain square.
     */
    private fun prepareNotificationIcons() {
        val size = 128
        val d = ContextCompat.getDrawable(this, R.mipmap.ic_launcher) ?: return
        val full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        val foreground: Drawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (d as? AdaptiveIconDrawable)?.foreground ?: d
        } else {
            d
        }
        val src = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val fgCanvas = Canvas(src)
        // Zoom past the adaptive 108dp grid: the artwork occupies only the
        // centre of that viewport, drawn unscaled it would silhouette small.
        val inset = size / 4
        foreground.setBounds(-inset, -inset, size + inset, size + inset)
        foreground.draw(fgCanvas)
        val silhouette = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        src.getPixels(pixels, 0, size, 0, 0, size, size)
        for (i in pixels.indices) {
            pixels[i] = (pixels[i] and 0xFF000000.toInt()) or 0x00FFFFFF
        }
        silhouette.setPixels(pixels, 0, size, 0, 0, size, size)
        TransferNotifier.setAppIcons(androidx.core.graphics.drawable.IconCompat.createWithBitmap(silhouette), full)
    }
}
