package com.linan.barezen_drive.platform

import android.os.Build

/**
 * The concrete device model (for example "Pixel 8" or "2210132C"), so the
 * album tree can tell multiple Android devices apart. Sanitized to a safe
 * folder name; falls back to "Android" when the model is blank.
 */
actual fun deviceName(): String {
    val model = Build.MODEL?.trim().orEmpty()
    if (model.isEmpty()) return "Android"
    val safe = model.replace(Regex("[/\\\\:*.?\"<>|]"), "-").trim('-', ' ', '.')
    return safe.ifEmpty { "Android" }
}
