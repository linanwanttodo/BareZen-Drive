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
                val parts = ArrayList<ByteArray>()
                var total = 0
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ch.readAvailable(buf, 0, buf.size)
                    if (n == -1) break
                    parts.add(buf.copyOf(n))
                    total += n
                }
                val bytes = ByteArray(total)
                var pos = 0
                for (p in parts) {
                    p.copyInto(bytes, pos)
                    pos += p.size
                }
                val i8 = Int8Array(bytes.size)
                for (i in bytes.indices) i8[i] = bytes[i]
                val blob = Blob(arrayOf<JsAny?>(i8).toJsArray())
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
