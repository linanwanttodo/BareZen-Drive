package com.linan.barezen_drive.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.launch

/**
 * The stock [ActivityResultContracts.CreateDocument] locks its mime type into
 * the contract instance, so every saved file would be stamped
 * application/octet-stream and other apps would no longer know what to open
 * it with. This variant carries the mime per launch instead.
 */
private class CreateDocumentTyped :
    ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

@Composable
actual fun rememberFileSaver(onDone: (String?) -> Unit): (name: String, mime: String?, open: suspend () -> ByteReadChannel) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, suspend () -> ByteReadChannel>?>(null) }
    val launcher = rememberLauncherForActivityResult(CreateDocumentTyped()) { uri ->
        val p = pending
        pending = null
        if (uri == null || p == null) {
            onDone(null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val ok = try {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    val ch = p.second()
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ch.readAvailable(buf, 0, buf.size)
                        if (n == -1) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                    true
                } ?: false
            } catch (t: Throwable) {
                false
            }
            onDone(if (ok) uri.toString() else null)
        }
    }
    return { name, mime, open ->
        pending = name to open
        launcher.launch(name to mime.orEmpty().ifBlank { "application/octet-stream" })
    }
}
