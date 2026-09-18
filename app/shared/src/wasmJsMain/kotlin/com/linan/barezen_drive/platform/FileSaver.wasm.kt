package com.linan.barezen_drive.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.browser.document
import kotlinx.coroutines.launch
import org.khronos.webgl.Int8Array
import org.khronos.webgl.set
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import kotlin.js.toJsArray

@Composable
actual fun rememberFileSaver(onDone: (String?) -> Unit): (name: String, mime: String?, open: suspend () -> ByteReadChannel) -> Unit {
    val scope = rememberCoroutineScope()
    return { name, _, open ->
        scope.launch {
            try {
                val ch = open()
                // Each chunk becomes a Blob part directly; the Blob constructor
                // concatenates natively at save time. The old code held the
                // chunk list, merged everything into one ByteArray and then
                // copied that per byte into one big Int8Array - three full
                // copies, so a few hundred MB peaked the tab's heap. No bulk
                // ByteArray -> Int8Array conversion exists in the wasm stdlib
                // (checked Kotlin 2.4.20 sources), so the per-element bridge
                // stays, but it runs per 64 KiB chunk instead of once over the
                // merged file: one pass instead of two, one copy of the file
                // in memory instead of three.
                val parts = ArrayList<JsAny?>()
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ch.readAvailable(buf, 0, buf.size)
                    if (n == -1) break
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    val view = Int8Array(chunk.size)
                    for (i in chunk.indices) view[i] = chunk[i]
                    parts.add(view)
                }
                val blob = Blob(parts.toTypedArray().toJsArray())
                val url = URL.createObjectURL(blob)
                val a = document.createElement("a").unsafeCast<HTMLAnchorElement>()
                a.href = url
                a.download = name
                document.body?.appendChild(a)
                a.click()
                a.parentNode?.removeChild(a)
                URL.revokeObjectURL(url)
                onDone(name)
            } catch (t: Throwable) {
                onDone(null)
            }
        }
    }
}
