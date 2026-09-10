package com.linan.barezen_drive.data.local

import android.content.Context
import com.linan.barezen_drive.AndroidContext

actual object AppPreferences {
    private val prefs by lazy {
        AndroidContext.app.getSharedPreferences("barezen_app_prefs", Context.MODE_PRIVATE)
    }

    actual fun get(): AppPrefs = object : AppPrefs {
        override var filesViewMode: Int
            get() = prefs.getInt("files_view_mode", 0)
            set(v) = prefs.edit().putInt("files_view_mode", v).apply()

        override var wallpaperEnabled: Boolean
            get() = prefs.getBoolean("wallpaper_enabled", false)
            set(v) = prefs.edit().putBoolean("wallpaper_enabled", v).apply()

        override var username: String
            get() = prefs.getString("username", "") ?: ""
            set(v) = prefs.edit().putString("username", v).apply()

        override var themeMode: Int
            get() = prefs.getInt("theme_mode", 1)
            set(v) = prefs.edit().putInt("theme_mode", v).apply()

        override var pdfViewMode: Int
            get() = prefs.getInt("pdf_view_mode", 0)
            set(v) = prefs.edit().putInt("pdf_view_mode", v).apply()

        override var accentColor: Int
            get() = prefs.getInt("accent_color", 0)
            set(v) = prefs.edit().putInt("accent_color", v).apply()

        override var useDynamicColor: Boolean
            get() = prefs.getBoolean("use_dynamic_color", false)
            set(v) = prefs.edit().putBoolean("use_dynamic_color", v).apply()

        override var glassBlurEnabled: Boolean
            get() = prefs.getBoolean("glass_blur_enabled", true)
            set(v) = prefs.edit().putBoolean("glass_blur_enabled", v).apply()

        override var glassAlphaPercent: Int
            get() = prefs.getInt("glass_alpha_percent", 60)
            set(v) = prefs.edit().putInt("glass_alpha_percent", v).apply()

        override var languageMode: Int
            get() = prefs.getInt("language_mode", 0)
            set(v) = prefs.edit().putInt("language_mode", v).apply()
    }
}
