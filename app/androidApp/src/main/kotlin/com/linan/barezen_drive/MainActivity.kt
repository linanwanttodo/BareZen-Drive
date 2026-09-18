package com.linan.barezen_drive

import android.Manifest
import android.content.Intent
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
        forwardTransferTap(intent)
        maybeRequestNotificationPermission()
        maybeRequestMediaReadPermission()

        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The activity is single-top (launchMode in the manifest): taps on a
        // notification while the app is foregrounded arrive here, not in
        // onCreate.
        forwardTransferTap(intent)
    }

    /**
     * Notification tap -> shared deep-link flag. The shared App() watches the
     * flag and pushes the transfer centre; this side only translates the
     * intent extra so navigation stays owned by the common layer.
     */
    private fun forwardTransferTap(intent: Intent?) {
        if (intent?.getBooleanExtra(
                com.linan.barezen_drive.platform.TransferNotifier.EXTRA_OPEN_TRANSFERS,
                false,
            ) == true
        ) {
            com.linan.barezen_drive.platform.TransferDeepLink.request()
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        // One prompt per install: after a denial (and especially a permanent
        // one) the system silently no-ops further automatic requests, so re
        // asking on every launch would only flash a dead dialog. Users grant
        // later through the system settings.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!granted && !prefs.getBoolean("notifications_asked", false)) {
            prefs.edit().putBoolean("notifications_asked", true).apply()
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun maybeRequestMediaReadPermission() {
        val wanted = when {
            // Android 14 adds the partial-access grant: the user picks which
            // photos to share and VISUAL_USER_SELECTED (only) comes back
            // granted. Treating that as "still missing" would re-prompt with
            // a dialog that can never succeed.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        val partialOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (missing.isNotEmpty() && !partialOk && !prefs.getBoolean("media_read_asked", false)) {
            prefs.edit().putBoolean("media_read_asked", true).apply()
            requestMediaRead.launch(missing.toTypedArray())
        }
    }

    private companion object {
        const val PREFS = "permissions"
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
