package com.linan.barezen_drive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    // Transfer progress notifications need POST_NOTIFICATIONS from Android 13.
    // The request is best-effort: declining only silences the shade, the
    // in-app transfer centre keeps working.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Album auto-sync reads the MediaStore; Android 13+ splits read access by
    // media type. Declining simply disables auto-sync, the rest of the app
    // keeps working.
    private val requestMediaRead =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidContext.init(applicationContext)
        com.linan.barezen_drive.platform.TransferNotifier.setSmallIcon(R.drawable.ic_notification)
        maybeRequestNotificationPermission()
        maybeRequestMediaReadPermission()

        setContent {
            App()
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun maybeRequestMediaReadPermission() {
        val wanted = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestMediaRead.launch(missing.toTypedArray())
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
