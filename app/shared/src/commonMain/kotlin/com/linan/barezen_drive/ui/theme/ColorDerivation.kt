package com.linan.barezen_drive.ui.theme

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import com.linan.barezen_drive.ui.wallpaper.WallpaperImage
import kotlin.math.abs

/**
 * Average color of the wallpaper pixels (straight mean of opaque samples).
 * Kept intentionally dependency-free: no palette library, just an arithmetic
 * mean. Used as the theme seed when a wallpaper is active.
 */
@Stable
fun averageColor(pixels: IntArray): Color {
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    var i = 0
    while (i < pixels.size) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        if (a > 127) {
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
            n++
        }
        i++
    }
    if (n == 0L) return Color(0xFF607D8B)
    return Color(
        red = (r / n).toInt() / 255f,
        green = (g / n).toInt() / 255f,
        blue = (b / n).toInt() / 255f,
        alpha = 1f,
    )
}

/**
 * Stable avatar color derived only from the username text (FNV-1a), mapped to
 * a fixed palette of solid hues. The same name always yields the same color
 * on every platform and restart; colors are plain solid fills.
 */
fun avatarColor(username: String): Color {
    if (username.isBlank()) return Color(0xFF607D8B)
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
    for (c in username) {
        hash = hash xor c.code.toLong()
        hash *= 0x100000001b3L
    }
    val palette = intArrayOf(
        0xFF1E88E5.toInt(), // blue
        0xFF43A047.toInt(), // green
        0xFF8E24AA.toInt(), // purple
        0xFFD81B60.toInt(), // pink red
        0xFFF4511E.toInt(), // deep orange
        0xFF00897B.toInt(), // teal
        0xFF6D4C41.toInt(), // brown
        0xFF3949AB.toInt(), // indigo
    )
    return Color(palette[abs(hash.toInt()) % palette.size])
}

/** First character of the name upper-cased; a dot for blank names. */
fun avatarLetter(username: String): String =
    username.trim().firstOrNull()?.uppercase()?.take(1) ?: "."

/** Wallpaper-derived seed color, or null when no wallpaper is active. */
fun wallpaperSeedColor(wallpaper: WallpaperImage?): Color? =
    wallpaper?.let { averageColor(it.pixels) }
