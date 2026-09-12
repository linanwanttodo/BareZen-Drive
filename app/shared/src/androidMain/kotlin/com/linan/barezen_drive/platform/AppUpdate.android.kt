package com.linan.barezen_drive.platform

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.linan.barezen_drive.AndroidContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume

actual val installChannel: InstallChannel = InstallChannel.ANDROID

actual fun openInBrowser(url: String) {
    runCatching {
        AndroidContext.app.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Packaged app: the user installs the new APK from the release page, so there
 * is no in-process reload to perform.
 */
actual fun reloadApp() = Unit

/**
 * Downloads the APK with the system DownloadManager (visible in the shade),
 * then hands the finished file to the package installer. Android 8+ asks the
 * user to allow installs from this app once (REQUEST_INSTALL_PACKAGES).
 */
actual suspend fun downloadAndInstallUpdate(url: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val context = AndroidContext.app
        val name = url.substringBefore('?').substringAfterLast('/')
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)

        val downloader = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var downloadId = -1L

        // The completion receiver MUST be live before enqueue: on a fast
        // network the broadcast can otherwise land before registration, the
        // wait would time out, and the flow would fall back to the browser.
        lateinit var receiver: BroadcastReceiver
        val completed = suspendCancellableCoroutine { continuation ->
            receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val ok = downloader.query(query).use { cursor ->
                        cursor.moveToFirst() &&
                            cursor.getInt(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS),
                            ) == DownloadManager.STATUS_SUCCESSFUL
                    }
                    context.unregisterReceiver(this)
                    if (continuation.isActive) continuation.resume(ok)
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(destination))
            downloadId = downloader.enqueue(request)
        }

        val ok = withTimeout(15 * 60 * 1000L) { completed } || run {
            // The broadcast may already have fired before this line; the queue
            // is the source of truth either way.
            val query = DownloadManager.Query().setFilterById(downloadId)
            downloader.query(query).use { cursor ->
                cursor.moveToFirst() &&
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                        DownloadManager.STATUS_SUCCESSFUL
            }
        }
        if (!ok) return@withContext false

        installApk(context, destination)
        true
    }.getOrDefault(false)
}

/** Opens the system package installer for the downloaded file. */
private fun installApk(context: Context, apk: File) {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    val install = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(install)
}
