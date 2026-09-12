package com.linan.barezen_drive.platform

import android.os.Build
import android.provider.Settings
import com.linan.barezen_drive.AndroidContext

/**
 * The album-tree device folder name: the user's own device name from system
 * settings when set (a friendly label like "米13" or "Pixel 8"), falling back
 * to the model string, then "Android". Build.MODEL alone is often a factory
 * code ("2210132C"), which reads like garbage in the device picker.
 */
actual fun deviceName(): String {
    val friendly = runCatching {
        Settings.Global.getString(
            AndroidContext.app.contentResolver,
            Settings.Global.DEVICE_NAME,
        )
    }.getOrNull()?.trim().orEmpty()
    val model = Build.MODEL?.trim().orEmpty()
    val brand = Build.BRAND?.trim().orEmpty()
    // No user-set name: brand + model reads better than the bare factory code.
    val name = when {
        friendly.isNotEmpty() -> friendly
        model.isNotEmpty() && brand.isNotEmpty() && !model.equals(brand, true) -> "$brand $model"
        model.isNotEmpty() -> model
        else -> "Android"
    }
    val safe = name.replace(Regex("[/\\\\:*.?\"<>|]"), "-").trim('-', ' ', '.')
    return safe.ifEmpty { "Android" }
}

actual fun legacyDeviceName(): String? {
    val model = Build.MODEL?.trim().orEmpty()
    val current = deviceName()
    return model.takeIf { it.isNotEmpty() && it != current }
}
