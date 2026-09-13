package com.linan.barezen_drive.platform

import android.app.WallpaperManager
import android.os.Build
import androidx.compose.ui.graphics.Color
import com.linan.barezen_drive.AndroidContext

actual fun systemAccentColor(): Color? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return runCatching {
        val colors = WallpaperManager.getInstance(AndroidContext.app)
            .getWallpaperColors(1) ?: return null
        colors.primaryColor?.let { Color(it.toArgb()) }
    }.getOrNull()
}
