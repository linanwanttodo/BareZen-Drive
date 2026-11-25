package com.linan.barezen_drive.platform

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.linan.barezen_drive.AndroidContext
import com.linan.barezen_drive.data.evidence.AutoLockPolicy
import com.linan.barezen_drive.data.evidence.EvidenceController
import com.linan.barezen_drive.data.evidence.EvidenceMode
import com.linan.barezen_drive.data.evidence.EvidencePlaybackRequest
import com.linan.barezen_drive.evidence.AndroidEvidenceController
import java.io.File

object AndroidEvidencePlatform : EvidencePlatform {
    override val controller: EvidenceController by lazy { AndroidEvidenceController() }

    @Composable
    override fun SecureContent(content: @Composable () -> Unit) {
        val activity = AndroidContext.activity
        DisposableEffect(activity) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        content()
    }

    @Composable
    override fun AutoLock(onLock: () -> Unit) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val latest by rememberUpdatedState(onLock)
        val handler = remember { Handler(Looper.getMainLooper()) }
        val policy = remember { AutoLockPolicy() }
        val lock = Runnable { if (policy.shouldLock()) latest() }
        val receiver = remember {
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        policy.onBackground()
                        handler.removeCallbacks(lock)
                        handler.postDelayed(lock, 30_000)
                    }
                }
            }
        }
        DisposableEffect(lifecycle) {
            val context = AndroidContext.app
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        policy.onBackground()
                        handler.postDelayed(lock, 30_000)
                    }
                    Lifecycle.Event.ON_START -> {
                        policy.onForeground()
                        handler.removeCallbacks(lock)
                    }
                    else -> Unit
                }
            }
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            lifecycle.addObserver(observer)
            onDispose {
                context.unregisterReceiver(receiver)
                lifecycle.removeObserver(observer)
                handler.removeCallbacks(lock)
            }
        }
    }

    @Composable
    override fun rememberRecordingPermissionRequest(onResult: (Boolean) -> Unit): (EvidenceMode) -> Unit {
        val context = LocalContext.current
        val latest by rememberUpdatedState(onResult)
        var mode = EvidenceMode.AUDIO
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            latest(grants.values.all { it })
        }
        return request@{ requested ->
            mode = requested
            val permissions = buildList {
                if (requested == EvidenceMode.VIDEO) add(Manifest.permission.CAMERA)
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val missing = permissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) latest(true) else launcher.launch(missing.toTypedArray())
        }
    }

    @Composable
    override fun Player(request: EvidencePlaybackRequest, onClose: () -> Unit) {
        val context = LocalContext.current
        val player = remember(request.token) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(File(request.path))))
                prepare()
                playWhenReady = true
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player } },
            update = { it.player = player },
            modifier = Modifier,
        )
    }
}

@Composable
actual fun evidencePlatform(): EvidencePlatform? = AndroidEvidencePlatform

actual fun evidenceNowMs(): Long = System.currentTimeMillis()
