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

    /** Brand silhouette; the app module injects its drawable at startup. */
    private var smallIconRes: Int = android.R.drawable.stat_sys_upload

    /**
     * App-branded icons injected at startup: the small icon is the launcher
     * icon rendered as a white alpha silhouette (the shade only ever draws a
     * flat tint from the small icon's alpha channel - a coloured bitmap there
     * would just read as a block), the large icon is the launcher icon in
     * full colour on the right of the notification.
     */
    private var smallIconIcon: androidx.core.graphics.drawable.IconCompat? = null
    private var largeIconBitmap: android.graphics.Bitmap? = null

    fun setSmallIcon(resId: Int) {
        smallIconRes = resId
    }

    fun setAppIcons(small: androidx.core.graphics.drawable.IconCompat, large: android.graphics.Bitmap) {
        smallIconIcon = small
        largeIconBitmap = large
    }

    /**
     * The app-injected small icon, for the other notification builders in the
     * app (the backup foreground notification hard-coding a system drawable is
     * how the shade ended up showing the platform robot mid-backup).
     */
    fun iconRes(): Int = smallIconRes

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

    private fun builder(channel: String, title: String, text: String?): NotificationCompat.Builder {
        val b = NotificationCompat.Builder(AndroidContext.app, channel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        // The app-branded pair wins when injected: coloured launcher artwork in
        // the large-icon slot, white alpha silhouette of the same artwork as
        // the small icon. The res fallback only covers a startup that somehow
        // skipped the injection.
        smallIconIcon?.let { b.setSmallIcon(it) } ?: b.setSmallIcon(smallIconRes)
        largeIconBitmap?.let { b.setLargeIcon(it) }
        b.setContentTitle(title)
        text?.let { b.setContentText(it) }
        return b
    }

    actual fun showProgress(tag: String, title: String, text: String?, fraction: Float?) {
        runCatching {
            ensureChannels()
            val nm = manager()?.takeIf { canNotify() } ?: return
            val b = builder(CHANNEL_PROGRESS, title, text)
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
            val b = NotificationCompat.Builder(AndroidContext.app, CHANNEL_DONE)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentTitle(title)
                .setContentText(text)
            smallIconIcon?.let { b.setSmallIcon(it) } ?: b.setSmallIcon(smallIconRes)
            largeIconBitmap?.let { b.setLargeIcon(it) }
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
