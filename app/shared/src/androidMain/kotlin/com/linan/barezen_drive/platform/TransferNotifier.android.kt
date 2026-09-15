package com.linan.barezen_drive.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.linan.barezen_drive.AndroidContext

/**
 * Ongoing transfer notifications in the shade. One channel for progress and a
 * stable notification id per tag, so repeated calls update the same entry.
 * Missing POST_NOTIFICATIONS permission on Android 13+ degrades to a silent
 * no-op (the in-app transfer centre still shows everything).
 */
actual object TransferNotifier {
    private const val CHANNEL_PROGRESS = "transfers"
    private const val CHANNEL_DONE = "transfer_results"
    private var counter = 0
    private val ids = mutableMapOf<String, Int>()

    /**
     * The app's own icon, injected once at startup by the app module: per
     * product decision both notification slots carry the launcher icon
     * itself, scaled down - the small icon just like the full-colour large
     * one. No silhouette, no recolouring.
     *
     * It is a bitmap on purpose. The shade has two ways to end up showing a
     * stock system figure instead of the product mark, and both are closed
     * here: a VectorDrawable that the shade cannot inflate (the album backup
     * notification used to ship `R.drawable.ic_notification`, a vector, and it
     * fell back to the platform's own drawing), and a platform drawable such as
     * `android.R.drawable.stat_sys_upload`, which was this field's initial
     * value. Nothing in this file may reach for either again.
     */
    private var smallIcon: androidx.core.graphics.drawable.IconCompat? = null
    private var largeIconBitmap: android.graphics.Bitmap? = null

    fun setAppIcons(small: androidx.core.graphics.drawable.IconCompat, large: android.graphics.Bitmap) {
        smallIcon = small
        largeIconBitmap = large
    }

    /**
     * The app-branded small icon, for the other notification builders in the
     * app - the album backup foreground notification used to hard-code a system
     * drawable, which is exactly how the shade ended up showing the platform
     * figure mid-backup.
     *
     * Falls back to the manifest icon (still the product's artwork, just the
     * masked launcher version) for the window before the injection has run.
     */
    fun smallIcon(): androidx.core.graphics.drawable.IconCompat? =
        smallIcon ?: AndroidContext.app.applicationInfo.icon
            .takeIf { it != 0 }
            ?.let { androidx.core.graphics.drawable.IconCompat.createWithResource(AndroidContext.app, it) }

    private fun manager(): NotificationManagerCompat? =
        runCatching { NotificationManagerCompat.from(AndroidContext.app) }.getOrNull()

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                AndroidContext.app,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = AndroidContext.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_PROGRESS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROGRESS,
                    "Transfers",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Upload and download progress" },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DONE,
                    "Transfer results",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    private fun idFor(tag: String): Int = ids.getOrPut(tag) { ++counter }

    /**
     * Single place the app's own icons are applied, so no builder in this file
     * can drift onto a different one. Null when no small icon could be resolved
     * at all: a notification without one is either dropped by the system or
     * drawn with its own fallback, and neither is acceptable.
     */
    private fun branded(channel: String): NotificationCompat.Builder? {
        val b = NotificationCompat.Builder(AndroidContext.app, channel)
        b.setSmallIcon(smallIcon() ?: return null)
        largeIconBitmap?.let { b.setLargeIcon(it) }
        return b
    }

    private fun builder(channel: String, title: String, text: String?): NotificationCompat.Builder? =
        branded(channel)?.apply {
            setOngoing(true)
            setOnlyAlertOnce(true)
            setPriority(NotificationCompat.PRIORITY_LOW)
            setContentTitle(title)
            text?.let { setContentText(it) }
        }

    actual fun showProgress(tag: String, title: String, text: String?, fraction: Float?) {
        runCatching {
            ensureChannels()
            val nm = manager()?.takeIf { canNotify() } ?: return
            val b = builder(CHANNEL_PROGRESS, title, text) ?: return
            if (fraction != null) {
                b.setProgress(100, (fraction.coerceIn(0f, 1f) * 100).toInt(), false)
            } else {
                b.setProgress(0, 0, true)
            }
            nm.notify(idFor(tag), b.build())
        }
    }

    actual fun showFinished(tag: String, title: String, text: String, ok: Boolean) {
        runCatching {
            ensureChannels()
            val nm = manager()?.takeIf { canNotify() } ?: return
            val b = branded(CHANNEL_DONE)?.apply {
                setOngoing(false)
                setPriority(NotificationCompat.PRIORITY_DEFAULT)
                setAutoCancel(true)
                setContentTitle(title)
                setContentText(text)
            } ?: return
            nm.notify(idFor(tag), b.build())
        }
    }

    actual fun dismiss(tag: String) {
        runCatching { manager()?.cancel(idFor(tag)) }
    }

    actual fun toast(text: String) {
        runCatching {
            android.widget.Toast.makeText(AndroidContext.app, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
