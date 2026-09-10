package com.linan.barezen_drive.data.local

import kotlinx.browser.window

private const val PREFIX = "bz_"

private fun readString(key: String): String? = try {
    window.localStorage.getItem(PREFIX + key)
} catch (_: Throwable) {
    null
}

private fun writeString(key: String, value: String) = try {
    window.localStorage.setItem(PREFIX + key, value)
} catch (_: Throwable) {
}

actual object AppPreferences {
    actual fun get(): AppPrefs = object : AppPrefs {
        override var filesViewMode: Int
            get() = readString("files_view_mode")?.toIntOrNull() ?: 0
            set(v) = writeString("files_view_mode", v.toString())

        override var wallpaperEnabled: Boolean
            get() = readString("wallpaper_enabled") == "1"
            set(v) = writeString("wallpaper_enabled", if (v) "1" else "0")

        override var username: String
            get() = readString("username") ?: ""
            set(v) = writeString("username", v)

        override var themeMode: Int
            get() = readString("theme_mode")?.toIntOrNull() ?: 1
            set(v) = writeString("theme_mode", v.toString())

        override var pdfViewMode: Int
            get() = readString("pdf_view_mode")?.toIntOrNull() ?: 0
            set(v) = writeString("pdf_view_mode", v.toString())

        override var accentColor: Int
            get() = readString("accent_color")?.toIntOrNull() ?: 0
            set(v) = writeString("accent_color", v.toString())

        override var useDynamicColor: Boolean
            get() = readString("use_dynamic_color") == "1"
            set(v) = writeString("use_dynamic_color", if (v) "1" else "0")

        override var glassBlurEnabled: Boolean
            get() = readString("glass_blur_enabled") != "0"
            set(v) = writeString("glass_blur_enabled", if (v) "1" else "0")

        override var glassAlphaPercent: Int
            get() = readString("glass_alpha_percent")?.toIntOrNull() ?: 60
            set(v) = writeString("glass_alpha_percent", v.toString())

        override var languageMode: Int
            get() = readString("language_mode")?.toIntOrNull() ?: 0
            set(v) = writeString("language_mode", v.toString())
    }
}
