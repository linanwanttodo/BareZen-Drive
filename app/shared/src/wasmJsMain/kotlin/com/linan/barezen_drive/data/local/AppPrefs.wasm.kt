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
    /**
     * One instance for the page, so `get()` costs nothing and callers can hold
     * on to it across recompositions.
     *
     * The getters deliberately still read localStorage on every access instead
     * of mirroring the values in memory: a cached layer would be faster and
     * would quietly break the second browser tab, which is a real way to use
     * this app (backup configured on the desktop, checked on the phone).
     */
    private val instance: AppPrefs = object : AppPrefs {
        override var filesViewMode: Int
            get() = readString("files_view_mode")?.toIntOrNull() ?: 0
            set(v) = writeString("files_view_mode", v.toString())

        override var serverFieldExpanded: Boolean
            get() = readString("server_field_expanded") == "1"
            set(v) = writeString("server_field_expanded", if (v) "1" else "0")

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

        override var albumViewMode: Int
            get() = readString("album_view_mode")?.toIntOrNull() ?: 0
            set(v) = writeString("album_view_mode", v.toString())

        override var albumColumns: Int
            get() = readString("album_columns")?.toIntOrNull() ?: 4
            set(v) = writeString("album_columns", v.toString())

        override var albumAutoSync: Boolean
            get() = readString("album_auto_sync") == "true"
            set(v) = writeString("album_auto_sync", v.toString())

        override var syncWifiOnly: Boolean
            get() = readString("sync_wifi_only") != "false"
            set(v) = writeString("sync_wifi_only", v.toString())

        // The web target has no background scheduler, so this is only carried so
        // the shared settings UI round-trips the value; MediaSync.apply ignores it.
        override var albumSyncChargingOnly: Boolean
            get() = readString("album_sync_charging_only") == "true"
            set(v) = writeString("album_sync_charging_only", v.toString())

        override var albumBucketsReviewed: Boolean
            get() = readString("album_buckets_reviewed") == "true"
            set(v) = writeString("album_buckets_reviewed", v.toString())
    }

    actual fun get(): AppPrefs = instance
}
