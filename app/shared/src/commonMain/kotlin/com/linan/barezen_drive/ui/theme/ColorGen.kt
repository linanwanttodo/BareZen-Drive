package com.linan.barezen_drive.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Generates a full Material 3 color scheme from one seed color using OKLCH
 * tonal palettes (the same role -> fixed-lightness mapping dynamic-color
 * systems use). Because every role reads a FIXED lightness tone and only the
 * hue/chroma follow the seed, text/surface contrast holds for ANY seed -
 * colors stop being "hardcoded" while staying readable.
 */
fun schemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
    val hue = seed.oklchHue()
    val chroma = seed.oklchChroma()

    fun tone(t: Double, c: Double = chroma) = oklchColor(t / 100.0, c.coerceAtMost(maxChromaFor(t, hue)), hue)
    fun neutral(t: Double) = tone(t, (chroma * 0.12).coerceAtLeast(0.008))
    fun neutralVariant(t: Double) = tone(t, (chroma * 0.35).coerceAtLeast(0.012))

    val primary = tone(if (dark) 78.0 else 40.0)
    val onPrimary = tone(if (dark) 18.0 else 100.0)
    val primaryContainer = tone(if (dark) 32.0 else 90.0)
    val onPrimaryContainer = tone(if (dark) 90.0 else 12.0)

    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = neutralVariant(80.0),
            onSecondary = neutralVariant(18.0),
            secondaryContainer = neutralVariant(30.0),
            onSecondaryContainer = neutralVariant(90.0),
            background = neutral(12.0),
            onBackground = neutral(90.0),
            surface = neutral(12.0),
            onSurface = neutral(90.0),
            surfaceVariant = neutral(26.0),
            onSurfaceVariant = neutralVariant(72.0),
            error = Color(0xFFF85149),
            onError = Color(0xFF0D1117),
            errorContainer = Color(0xFF3C1618),
            onErrorContainer = Color(0xFFFFC1BC),
            outline = neutral(42.0),
            outlineVariant = neutral(24.0),
            surfaceContainerLowest = neutral(7.0),
            surfaceContainerLow = neutral(11.0),
            surfaceContainer = neutral(15.0),
            surfaceContainerHigh = neutral(20.0),
            surfaceContainerHighest = neutral(25.0),
            surfaceDim = neutral(11.0),
            surfaceBright = neutral(25.0),
            inverseSurface = neutral(92.0),
            inverseOnSurface = neutral(14.0),
            inversePrimary = tone(40.0),
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = neutralVariant(38.0),
            onSecondary = Color.White,
            secondaryContainer = neutralVariant(92.0),
            onSecondaryContainer = neutralVariant(14.0),
            background = neutral(99.0),
            onBackground = neutral(13.0),
            surface = neutral(99.0),
            onSurface = neutral(13.0),
            surfaceVariant = neutral(94.0),
            onSurfaceVariant = neutralVariant(34.0),
            error = Color(0xFFCF222E),
            onError = Color.White,
            errorContainer = Color(0xFFFFEBE9),
            onErrorContainer = Color(0xFF82071E),
            outline = neutral(58.0),
            outlineVariant = neutral(86.0),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = neutral(97.5),
            surfaceContainer = neutral(95.0),
            surfaceContainerHigh = neutral(92.0),
            surfaceContainerHighest = neutral(88.0),
            inverseSurface = neutral(15.0),
            inverseOnSurface = neutral(96.0),
            inversePrimary = tone(78.0),
        )
    }
}

/** sRGB (0..1 components) -> OKLab L in 0..1, a, b. */
private fun linearSrgbToOklab(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
    val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    fun cube(v: Double) = v.pow(1.0 / 3.0)
    val l_ = cube(l); val m_ = cube(m); val s_ = cube(s)
    return Triple(
        0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
        1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
        0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_,
    )
}

private fun oklabToLinearSrgb(L: Double, a: Double, b: Double): Triple<Double, Double, Double> {
    val l_ = L + 0.3963377774 * a + 0.2158037573 * b
    val m_ = L - 0.1055613458 * a - 0.0638541728 * b
    val s_ = L - 0.0894841775 * a - 1.2914855480 * b
    val l = l_.pow(3); val m = m_.pow(3); val s = s_.pow(3)
    return Triple(
        +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
    )
}

private fun gamma(v: Double): Double =
    if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055

private fun delinearize(v: Double): Double =
    if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

private fun inGamut(r: Double, g: Double, b: Double): Boolean =
    r in -0.001..1.001 && g in -0.001..1.001 && b in -0.001..1.001

/** OKLCH (L 0..1, C, hue degrees) -> sRGB Color; chroma is reduced until in gamut. */
internal fun oklchTone(hueDeg: Double, chroma: Double, tone: Double): Color = oklchColor(tone / 100.0, chroma, hueDeg)

private fun oklchColor(L: Double, chroma: Double, hueDeg: Double): Color {
    val hue = hueDeg * PI / 180.0
    var c = chroma
    var color = Color.Gray
    for (i in 0 until 12) {
        val a = c * cos(hue)
        val b = c * sin(hue)
        val (lr, lg, lb) = oklabToLinearSrgb(L, a, b)
        if (inGamut(lr, lg, lb)) {
            color = Color(
                gamma(lr).coerceIn(0.0, 1.0).toFloat(),
                gamma(lg).coerceIn(0.0, 1.0).toFloat(),
                gamma(lb).coerceIn(0.0, 1.0).toFloat(),
            )
            break
        }
        c *= 0.9
    }
    return color
}

internal fun Color.oklchHue(): Double {
    val (L, a, b) = linearSrgbToOklab(
        delinearize(red.toDouble()),
        delinearize(green.toDouble()),
        delinearize(blue.toDouble()),
    )
    if (a == 0.0 && b == 0.0) return 260.0
    return ((atan2(b, a) * 180.0 / PI) + 360.0) % 360.0
}

internal fun Color.oklchChroma(): Double {
    val (L, a, b) = linearSrgbToOklab(
        delinearize(red.toDouble()),
        delinearize(green.toDouble()),
        delinearize(blue.toDouble()),
    )
    return sqrt(a * a + b * b).coerceAtLeast(0.04)
}

private fun maxChromaFor(tone: Double, hueDeg: Double): Double {
    // Binary-search the largest in-gamut chroma for this lightness/hue.
    val L = tone / 100.0
    val hue = hueDeg * PI / 180.0
    var lo = 0.0
    var hi = 0.4
    repeat(14) {
        val mid = (lo + hi) / 2
        val a = mid * cos(hue)
        val b = mid * sin(hue)
        val (lr, lg, lb) = oklabToLinearSrgb(L, a, b)
        if (inGamut(lr, lg, lb)) lo = mid else hi = mid
    }
    return lo
}
