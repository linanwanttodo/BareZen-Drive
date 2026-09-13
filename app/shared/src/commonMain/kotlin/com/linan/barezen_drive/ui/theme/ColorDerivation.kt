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
    // A plain average of any photo is mud-brown, so the wallpaper seed picks
    // the most prominent VIVID hue instead: 24 hue bins vote with saturation-
    // weighted counts and the winning bin's most saturated pixel is the seed.
    // Wallpapers without a clear hue fall back to the default blue.
    val votes = FloatArray(24)
    val best = arrayOfNulls<Color>(24)
    val bestWeight = FloatArray(24)
    var i = 0
    var colored = 0
    while (i < pixels.size) {
        val p = pixels[i]
        if ((p ushr 24) and 0xFF > 127) {
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            val saturation = if (max == 0f) 0f else delta / max
            val value = max
            // Near-gray / near-black / near-white pixels carry no hue.
            if (saturation > 0.25f && value in 0.15f..0.95f) {
                val hue = when {
                    delta == 0f -> 0f
                    max == r -> 60f * (((g - b) / delta + 6f) % 6f)
                    max == g -> 60f * (((b - r) / delta) + 2f)
                    else -> 60f * (((r - g) / delta) + 4f)
                }
                val bin = (hue / 15f).toInt().coerceIn(0, 23)
                val weight = saturation * saturation * value
                votes[bin] += weight
                if (weight > bestWeight[bin]) {
                    bestWeight[bin] = weight
                    best[bin] = Color(r, g, b)
                }
                colored++
            }
        }
        i++
    }
    if (colored == 0) return DefaultSeed
    val winner = votes.indices.maxBy { votes[it] }
    val seedPixel = best[winner] ?: return DefaultSeed
    // Re-express the winning pixel at full mid-lightness saturation so the
    // generated tonal palette keeps the hue with enough chroma to work with.
    val sr = seedPixel.red * 255f
    val sg = seedPixel.green * 255f
    val sb = seedPixel.blue * 255f
    val max = maxOf(sr, sg, sb) / 255f
    val min = minOf(sr, sg, sb) / 255f
    val delta = max - min
    var hue = when {
        delta == 0f -> 220f
        max == sr -> 60f * (((sg - sb) / delta + 6f) % 6f)
        max == sg -> 60f * (((sb - sr) / delta) + 2f)
        else -> 60f * (((sr - sg) / delta) + 4f)
    }
    val saturation = (if (max == 0f) 0f else delta / max).coerceIn(0.45f, 0.8f)
    return hslColor(hue, saturation)
}

/** Vivid mid-lightness color for an HSL hue/saturation pair. */
private fun hslColor(hue: Float, saturation: Float): Color {
    val l = 0.5f
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * saturation
    val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when (((hue % 360f) / 60f).toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
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
