package com.linan.barezen_drive.evidence.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.linan.barezen_drive.AndroidContext
import com.linan.barezen_drive.data.evidence.EvidenceErrorKind
import com.linan.barezen_drive.data.evidence.EvidenceLens
import com.linan.barezen_drive.data.evidence.EvidenceMode
import com.linan.barezen_drive.data.evidence.EvidenceRecordingState
import com.linan.barezen_drive.evidence.EvidenceDb
import com.linan.barezen_drive.evidence.EvidenceFiles
import com.linan.barezen_drive.evidence.EvidenceKeyBox
import com.linan.barezen_drive.evidence.recording.AudioSegmentRecorder
import com.linan.barezen_drive.evidence.recording.RecordingCoordinator
import com.linan.barezen_drive.evidence.recording.VideoSegmentRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class EvidenceRecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var coordinator: RecordingCoordinator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cameraExecutor: java.util.concurrent.ExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (coordinator == null) stopSelf(startId) else coordinator?.requestStop()
            return START_NOT_STICKY
        }
        if (coordinator != null) return START_NOT_STICKY
        val mode = intent?.getStringExtra(EXTRA_MODE)?.let { runCatching { EvidenceMode.valueOf(it) }.getOrNull() } ?: return invalidStart(startId)
        val lease = intent.getStringExtra(EXTRA_LEASE) ?: return invalidStart(startId)
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return invalidStart(startId)
        val vaultId = intent.getStringExtra(EXTRA_VAULT) ?: return invalidStart(startId)
        val serverKey = intent.getStringExtra(EXTRA_SERVER) ?: return invalidStart(startId)
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT) ?: return invalidStart(startId)
        val dek = EvidenceKeyBoxHolder.box.take(lease) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            startForegroundNotification(mode)
        } catch (_: Throwable) {
            dek.fill(0)
            EvidenceDb.finishSession(sessionId, "INTERRUPTED", System.currentTimeMillis())
            currentState.value = EvidenceRecordingState(error = EvidenceErrorKind.SERVICE_START_DENIED)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BareZen:EvidenceRecording").apply { acquire() }
        val sessionDir = EvidenceFiles.sessionDir(vaultId, sessionId).apply { mkdirs() }
        val executor = if (mode == EvidenceMode.VIDEO) Executors.newSingleThreadExecutor().also { cameraExecutor = it } else null
        val lifecycle = if (mode == EvidenceMode.VIDEO) RecordingLifecycleOwner().apply { start() } else null
        val recorder = when {
            lifecycle != null && executor != null -> VideoSegmentRecorder(
                this,
                EvidenceLens.valueOf(intent.getStringExtra(EXTRA_LENS) ?: EvidenceLens.BACK.name),
                lifecycle,
                executor,
            )
            else -> AudioSegmentRecorder(this)
        }
        val started = EvidenceDb.sessions(vaultId).firstOrNull { it.sessionId == sessionId }?.startedAtMs ?: System.currentTimeMillis()
        val active = RecordingCoordinator(
            scope, recorder, vaultId, sessionId, mode, started, dek,
            intent.getIntExtra(EXTRA_DEK_VERSION, 1), serverKey, accountId, sessionDir,
        ) { error -> finish(error) }
        coordinator = active
        active.start()
        scope.launch {
            active.state.collect { state ->
                currentState.value = state
                val manager = getSystemService(android.app.NotificationManager::class.java)
                manager.notify(EvidenceNotification.ID, EvidenceNotification.build(this@EvidenceRecordingService, state))
            }
        }
        return START_NOT_STICKY
    }

    private fun invalidStart(startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun startForegroundNotification(mode: EvidenceMode) {
        val state = EvidenceRecordingState(mode = mode)
        val type = if (Build.VERSION.SDK_INT < 29) 0 else when (mode) {
            EvidenceMode.AUDIO -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            EvidenceMode.VIDEO -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, EvidenceNotification.ID, EvidenceNotification.build(this, state), type)
        } else {
            startForeground(EvidenceNotification.ID, EvidenceNotification.build(this, state))
        }
    }

    private fun finish(error: EvidenceErrorKind?) {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        cameraExecutor?.shutdown()
        cameraExecutor = null
        coordinator = null
        currentState.value = EvidenceRecordingState(error = error)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        scope.cancel()
    }

    override fun onDestroy() {
        coordinator?.requestStop()
        if (coordinator == null) scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.linan.barezen_drive.evidence.START"
        const val ACTION_STOP = "com.linan.barezen_drive.evidence.STOP"
        const val EXTRA_MODE = "mode"
        const val EXTRA_LENS = "lens"
        const val EXTRA_LEASE = "lease"
        const val EXTRA_SESSION = "session"
        const val EXTRA_VAULT = "vault"
        const val EXTRA_SERVER = "server"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_DEK_VERSION = "dek_version"
        val currentState = MutableStateFlow(EvidenceRecordingState())

        fun start(intent: Intent) {
            intent.action = ACTION_START
            ContextCompat.startForegroundService(AndroidContext.app, intent)
        }
    }
}

object EvidenceKeyBoxHolder {
    val box = EvidenceKeyBox()
}
