package com.linan.barezen_drive.platform

import androidx.compose.runtime.Composable
import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement

@Composable
actual fun rememberImagePicker(onResult: (List<PickedFile>) -> Unit): () -> Unit {
    return {
        val input = document.createElement("input").unsafeCast<HTMLInputElement>()
        input.type = "file"
        input.accept = "image/*"
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
