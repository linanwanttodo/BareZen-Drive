package com.linan.barezen_drive.platform

import android.content.Intent
import android.net.Uri
import com.linan.barezen_drive.AndroidContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual val isAndroidPlatform: Boolean = true

private const val RELEASES_URL =
    "https://api.github.com/repos/linanwanttodo/BareZen-Drive/releases/latest"

private val TAG_REGEX = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

// Lightweight update probe: one plain HTTPS GET, no JSON parser - the tag is
// extracted with a regex because the response envelope is not part of the
// app's own API contract. Runs fully on a worker thread.
actual suspend fun latestReleaseTag(): String? = withContext(Dispatchers.IO) {
    runCatching<String?> {
        val conn = (java.net.URL(RELEASES_URL).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "BareZen-Drive")
        }
        try {
            if (conn.responseCode != 200) return@runCatching null
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            TAG_REGEX.find(body)?.groupValues?.get(1)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

actual fun openInBrowser(url: String) {
    runCatching {
        AndroidContext.app.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
