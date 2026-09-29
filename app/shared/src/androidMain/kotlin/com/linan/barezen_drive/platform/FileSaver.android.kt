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
actual fun rememberFileSaver(
    onDone: (FileSaveRequest, FileSaveResult) -> Unit,
    onProgress: (FileSaveRequest, Long) -> Unit,
): (FileSaveRequest) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<FileSaveRequest?>(null) }
    val launcher = rememberLauncherForActivityResult(CreateDocumentTyped()) { uri ->
        val p = pending
        pending = null
        if (uri == null || p == null) {
            // Backed out at the document picker: nothing was copied and nothing
            // went wrong, so the caller closes its row quietly instead of
            // telling the user their download failed.
            if (p != null) onDone(p, FileSaveResult.Cancelled)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = try {
                val out = ctx.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("openOutputStream returned null")
                out.use { stream ->
                    val ch = p.open()
                    val buf = ByteArray(1 shl 16)
                    var written = 0L
                    while (true) {
                        val n = ch.readAvailable(buf, 0, buf.size)
                        if (n == -1) break
                        stream.write(buf, 0, n)
                        written += n
                        // Reported per chunk: a large file used to be minutes of
                        // nothing at all after the dialog closed.
                        onProgress(p, written)
                    }
                    stream.flush()
                }
                FileSaveResult.Saved(uri.toString())
            } catch (t: Throwable) {
                // A throw before the first byte (no such document, revoked
                // permission, disk full on open) is still a failure: the caller
                // must not have to guess it from the absence of progress.
                // The throwable used to be dropped on the floor, so quota
                // exceeded / revoked uri / no space were indistinguishable.
                android.util.Log.w(
                    "FileSaver",
                    "save failed for ${p.name}: ${t::class.simpleName} ${t.message}",
                )
                FileSaveResult.Failed(t::class.simpleName ?: "error")
            }
            onDone(p, result)
        }
    }
    return { req ->
        pending = req
        launcher.launch(req.name to req.mime.orEmpty().ifBlank { "application/octet-stream" })
    }
}
