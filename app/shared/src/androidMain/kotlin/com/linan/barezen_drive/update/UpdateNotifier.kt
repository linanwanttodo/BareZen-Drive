package com.linan.barezen_drive.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.linan.barezen_drive.AndroidContext
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.platform.TransferNotifier

/**
 * The one thing the background updater shows: the package is on disk and verified,
 * and the only step left is the system's own confirmation. IMPORTANCE_DEFAULT so
 * it makes a sound - a silent notification nobody reads would leave the phone on
 * an old build forever, which is exactly what this is here to prevent.
 */
object UpdateNotifier {

    const val CHANNEL = "app_update"
    const val EXTRA_STAGED_APK = "com.linan.barezen_drive.extra.STAGED_APK"
    private const val NOTIFICATION_ID = 4200

    /**
     * The tap target lives in the app module, which this shared module cannot
     * name by class (the dependency only points the other way), so it is the
     * class name as a string - the same trick TransferNotifier's tap intent
     * already uses.
     */
    private const val MAIN_ACTIVITY = "com.linan.barezen_drive.MainActivity"

    fun ensureChannel() {
        val nm = AndroidContext.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, I18n.strings.notifyChannelUpdate, NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = I18n.strings.notifyChannelUpdateDesc },
            )
        }
    }

    /**
     * Post the "ready to install" prompt. Returns whether it actually went
     * out: the caller has to know, because a version remembered as staged
     * without an announced prompt is an upgrade hint lost forever.
     *
     * Every failure mode reports false rather than throwing: a failed prompt
     * must not kill the worker pass that already paid for the package. The
     * quiet paths the old void version returned through - permission refused,
     * no small icon resolvable, the system refusing the post - are now the
     * caller's signal to leave the staged key unwritten.
     */
    fun notifyReady(version: String, path: String): Boolean = runCatching {
        // Same capability gate the transfer notifications use: from Android
        // 13 on, a refused POST_NOTIFICATIONS makes notify() a silent no-op,
        // so "posted" would be a lie without checking first.
        if (!TransferNotifier.canNotify()) return@runCatching false
        ensureChannel()
        // The bitmap icon the app already installs for itself (see BareZenApp);
        // without one there is no notification to post.
        val small = TransferNotifier.smallIcon() ?: return@runCatching false
        val intent = Intent()
            .setClassName(AndroidContext.app, MAIN_ACTIVITY)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_STAGED_APK, path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // FLAG_IMMUTABLE is required from API 31 and accepted below it; without it
        // the tap throws on newer Android and the notification is a dead end.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(AndroidContext.app, NOTIFICATION_ID, intent, flags)
        val nm = AndroidContext.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(AndroidContext.app, CHANNEL)
                .setSmallIcon(small)
                .setContentTitle(I18n.strings.notifyUpdateReady(version))
                .setContentText(I18n.strings.notifyUpdateReadyText)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
        true
    }.getOrDefault(false)

    /** The package is installed or thrown away, so the prompt must not linger. */
    fun clear() {
        runCatching {
            (AndroidContext.app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        }
    }
}
