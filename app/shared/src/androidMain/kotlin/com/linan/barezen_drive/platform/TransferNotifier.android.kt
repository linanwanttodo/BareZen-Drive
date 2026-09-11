package com.linan.barezen_drive.platform

import android.app.Notification
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

    private fun manager(): NotificationManagerCompat? =
        runCatching { NotificationManagerCompat.from(AndroidContext.app) }.getOrNull()

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
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        text?.let { b.setContentText(it) }
        return b
    }

    actual fun showProgress(tag: String, title: String, text: String?, fraction: Float?) {
        runCatching {
            ensureChannels()
            val nm = manager() ?: return
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
            val nm = manager() ?: return
            val b = NotificationCompat.Builder(AndroidContext.app, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
