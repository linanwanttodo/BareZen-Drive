package com.linan.barezen_drive.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.linan.barezen_drive.AndroidContext

actual suspend fun copyToClipboard(text: String): Boolean = runCatching {
    val context = AndroidContext.app
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("text", text))
    true
}.getOrDefault(false)
