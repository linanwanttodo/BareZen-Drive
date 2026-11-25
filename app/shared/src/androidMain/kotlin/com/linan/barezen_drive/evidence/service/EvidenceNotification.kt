package com.linan.barezen_drive.evidence.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.linan.barezen_drive.AndroidContext
import com.linan.barezen_drive.data.evidence.EvidenceMode
import com.linan.barezen_drive.data.evidence.EvidenceRecordingState
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.platform.TransferNotifier

object EvidenceNotification {
    const val ID = 4701
    const val CHANNEL = "evidence_recording"

    fun build(context: Context, state: EvidenceRecordingState): android.app.Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, I18n.strings.tabEvidence, NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
        val open = Intent().setClassName(context, "${context.packageName}.MainActivity").apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val stop = Intent(context, EvidenceRecordingService::class.java).apply { action = EvidenceRecordingService.ACTION_STOP }
        val builder = NotificationCompat.Builder(context, CHANNEL)
        val icon = TransferNotifier.smallIcon()
        if (icon != null) builder.setSmallIcon(icon) else builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
        return builder
            .setContentTitle(if (state.mode == EvidenceMode.VIDEO) I18n.strings.evidenceVideo else I18n.strings.evidenceAudio)
            .setContentText("${state.sealedSegments} ${I18n.strings.evidenceSegments} · ${state.uploadLabel.orEmpty()}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, I18n.strings.evidenceStop, PendingIntent.getService(context, 2, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }
}
