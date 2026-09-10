package com.linan.barezen_drive.data.local

/**
 * Cross-platform app preferences (non-secret): UI view mode, theme wallpaper, etc.
 * Backed by SharedPreferences on Android and localStorage on wasmJs.
 */
interface AppPrefs {
    /** File list view mode: 0 = list (default), 1 = grid. */
    var filesViewMode: Int

    /** Wallpaper mode: 0 = none (flat color, default), 1 = user image. */
    var wallpaperEnabled: Boolean

    /** Remembered login name shown by the account avatar; blank when unknown. */
    var username: String

    /** Theme selection: 0 = system, 1 = light (default), 2 = dark. */
    var themeMode: Int

    /** PDF viewer layout: 0 = single page (default), 1 = continuous long-image. */
    var pdfViewMode: Int

    /** Accent seed ARGB; 0 = default (GitHub blue). */
    var accentColor: Int

    /** Follow system dynamic color (Android 12+ wallpaper palette). */
    var useDynamicColor: Boolean

    /** Liquid glass blur on the bottom pill; off = plain translucent. */
    var glassBlurEnabled: Boolean

    /** Glass surface tint opacity percent (10-91). */
    var glassAlphaPercent: Int

    /** UI language: 0 = follow the system (default), 1 = Chinese, 2 = English. */
    var languageMode: Int
}

expect object AppPreferences {
    fun get(): AppPrefs
}
