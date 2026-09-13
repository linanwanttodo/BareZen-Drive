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

    /** Glass surface tint opacity percent (30-91). */
    var glassAlphaPercent: Int

    /** UI language: 0 = follow the system (default), 1 = Chinese, 2 = English. */
    var languageMode: Int

    /** Album photo-grid layout: 0 waterfall (default), 1 uniform, 2 by date. */
    var albumViewMode: Int

    /** Album uniform/dated grid column count (pinch-to-zoom), clamped 2-6; default 4. */
    var albumColumns: Int

    /** Album auto-sync: upload new device photos automatically. */
    var albumAutoSync: Boolean

    /** Auto-sync over Wi-Fi only (false = also mobile data). */
    var syncWifiOnly: Boolean

    /** Auto-sync only while the device is charging (default off). */
    var albumSyncChargingOnly: Boolean

    /** True once the first-run "review which albums to back up" prompt has been
     *  answered, so it is offered exactly once. */
    var albumBucketsReviewed: Boolean
}

expect object AppPreferences {
    fun get(): AppPrefs
}
