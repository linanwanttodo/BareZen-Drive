package com.linan.barezen_drive.platform

import androidx.compose.runtime.Composable
import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.w3c.dom.HTMLInputElement
import org.w3c.files.Blob
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class WasmPickedFile(internal val file: File) : PickedFile {
    override val name: String = file.name
    override val size: Long = file.size.toDouble().toLong()
    override val mimeType: String? = file.type.ifBlank { null }

    override suspend fun readRange(offset: Long, length: Int): ByteArray? {
        if (offset >= size || length <= 0) return null
        val end = minOf(offset + length, size)
        val blob = file.slice(offset.toInt(), end.toInt())
        val ab = blob.readAsArrayBufferSuspending()
        val i8 = Int8Array(ab)
        return ByteArray(i8.length) { i8[it] }
    }
}

private suspend fun Blob.readAsArrayBufferSuspending(): ArrayBuffer =
    suspendCancellableCoroutine { cont ->
        val reader = FileReader()
        reader.onload = { cont.resume(reader.result!!.unsafeCast()) }
        reader.onerror = { cont.resumeWithException(IllegalStateException("file read failed")) }
        reader.readAsArrayBuffer(this)
    }

@Composable
actual fun rememberFilePicker(onResult: (List<PickedFile>) -> Unit): () -> Unit {
    return {
        val input = document.createElement("input").unsafeCast<HTMLInputElement>()
        input.type = "file"
        input.multiple = true
        input.onchange = {
            val files = input.files
            if (files != null) {
                val list = (0 until files.length).mapNotNull { files.item(it)?.let(::WasmPickedFile) }
                onResult(list)
            }
        }
        input.click()
    }
}
