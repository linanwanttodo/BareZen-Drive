package com.linan.barezen_drive.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.linan.barezen_drive.AndroidContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
 * Streams the APK into the app's own download directory with live progress,
 * then hands the finished file to the package installer. No system
 * DownloadManager, no completion broadcasts, no OEM quirks: the progress the
 * UI shows is the bytes this process actually wrote. Android 8+ asks the user
 * to allow installs from this app once (REQUEST_INSTALL_PACKAGES).
 *
 * [urls] are mirrors of the same package (server proxy first, upstream link
 * as fallback) and are tried in order: whether the flaky hop is the phone's
 * route to GitHub or the server's route to GitHub, one of the two sources
 * carries the download. A mirror only wins when the file it produced matches
 * the manifest's [sha256]/[size] - see [verifyDownloaded].
 */
actual suspend fun downloadAndInstallUpdate(
    urls: List<String>,
    sha256: String?,
    size: Long?,
    onProgress: (Float) -> Unit,
): Boolean = withContext(Dispatchers.IO) {
    urls.firstOrNull { url ->
        runCatching { downloadOne(url, sha256, size, onProgress) }.getOrDefault(false)
    } != null
}

/** Downloads one mirror; false leaves [urls]' next candidate to take over. */
private fun downloadOne(
    url: String,
    sha256: String?,
    size: Long?,
    onProgress: (Float) -> Unit,
): Boolean {
    val context = AndroidContext.app
    val name = url.substringBefore('?').substringAfterLast('/')
    val destination = File(context.getExternalFilesDir(null), "updates/$name")
    destination.parentFile?.mkdirs()

    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = true
    }
    try {
        if (connection.responseCode !in 200..299) return false
        val total = connection.contentLengthLong
        destination.outputStream().use { out ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(128 * 1024)
                var written = 0L
                var lastPercent = -1
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    out.write(buffer, 0, n)
                    written += n
                    if (total > 0) {
                        val percent = (written * 100 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent.coerceIn(0, 100) / 100f)
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        // A truncated file must never reach the installer: drop it so the
        // next mirror starts clean instead of resuming garbage.
        destination.delete()
        return false
    } finally {
        connection.disconnect()
    }

    // A file that does not match the manifest is worse than no file: the
    // installer would reject it with a message the user cannot act on. Drop it
    // so the next mirror starts clean, exactly like a broken connection.
    if (!verifyDownloaded(destination, sha256, size)) {
        destination.delete()
        return false
    }

    // Download is complete; report the last percent before handing the file
    // to the installer so the UI never ends on 99.
    onProgress(1f)
    installApk(context, destination)
    return true
}

/**
 * Confirms the bytes on disk are the ones the release manifest promised.
 *
 * The size is checked first because it is free and catches the common relay
 * fault (a proxy that forwarded a Content-Length it did not actually carry);
 * the digest then covers everything size cannot, such as a mirror serving the
 * wrong tag. An absent expectation is skipped rather than failed, so builds
 * reading an older manifest without these fields keep updating.
 *
 * The digest comes from [Sha256er], the same primitive the upload path uses,
 * so an APK hashed here and a file hashed there are directly comparable.
 */
private fun verifyDownloaded(file: File, sha256: String?, size: Long?): Boolean {
    if (!file.isFile) return false
    if (size != null && size > 0L && file.length() != size) return false
    val expected = sha256?.trim().orEmpty()
    if (expected.isEmpty()) return true
    val hasher = Sha256er.newInstance()
    file.inputStream().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            // A full buffer goes in as-is; only the final short read needs a
            // copy, so the common path allocates nothing.
            hasher.update(if (n == buffer.size) buffer else buffer.copyOf(n))
        }
    }
    return hasher.digestHex().equals(expected, ignoreCase = true)
}

/** Opens the system package installer for the downloaded file. */
private fun installApk(context: Context, apk: File) {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    val install = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(install)
}
