package com.linan.barezen_drive.platform

import kotlinx.browser.document
import kotlinx.coroutines.await
import org.w3c.dom.HTMLTextAreaElement
import kotlin.js.Promise

/** Subset of the browser Clipboard interface used here. */
private external interface ClipboardApi {
    fun writeText(text: String): Promise<JsAny?>
}

/** navigator.clipboard only exists in secure contexts (https / localhost). */
private val clipboardApi: ClipboardApi? =
    js("typeof navigator !== 'undefined' && navigator.clipboard !== undefined ? navigator.clipboard : null")

/**
 * Web clipboard: the async Clipboard API needs a secure context (https), which
 * a self-hosted http deployment does not have, so fall back to the classic
 * hidden-textarea + execCommand trick that works from any user gesture.
 */
actual suspend fun copyToClipboard(text: String): Boolean {
    val clip = clipboardApi
    if (clip != null) {
        val ok = runCatching {
            clip.writeText(text).await()
            true
        }.getOrDefault(false)
        if (ok) return true
    }
    return execCommandCopy(text)
}

private fun execCommandCopy(text: String): Boolean = runCatching {
    val textarea = document.createElement("textarea") as HTMLTextAreaElement
    textarea.value = text
    textarea.style.setProperty("position", "fixed")
    textarea.style.setProperty("opacity", "0")
    document.body?.appendChild(textarea)
    textarea.focus()
    textarea.select()
    val ok = document.execCommand("copy")
    document.body?.removeChild(textarea)
    ok
}.getOrDefault(false)
