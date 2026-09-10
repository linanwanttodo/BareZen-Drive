package com.linan.barezen_drive.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

@Composable
actual fun rememberFileSaver(onDone: (String?) -> Unit): (name: String, mime: String?, open: suspend () -> ByteReadChannel) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, suspend () -> ByteReadChannel>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
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
    return { name, _, open ->
        pending = name to open
        launcher.launch(name)
    }
}
