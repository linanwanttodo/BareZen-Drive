package com.linan.barezen_drive.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/** Default accent: GitHub blue. Users can override it in settings. */
val DefaultSeed = Color(0xFF0969DA)

/**
 * Theme selection persisted by the settings screen.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Opacity of the frosted page panel between the wallpaper and screen content.
 * FIXED at 0.6 over a wallpaper (NPatch BG_SURFACE_ALPHA): dark text stays
 * readable on light theme and light text on dark theme at any wallpaper.
 * Flat mode (no wallpaper) uses the opaque surface. NOT user-tunable - the
 * readability slider only affects cards.
 */
val LocalPanelAlpha = androidx.compose.runtime.staticCompositionLocalOf { 0.6f }

/**
 * Card opacity (0.3-0.91, the settings slider). Cards float on top of the
 * page panel; the user can trade translucency for text punch here.
 */
val LocalCardAlpha = androidx.compose.runtime.staticCompositionLocalOf { 0.6f }

/**
 * Whether the dark tones are active, as [AppTheme] resolved them. Anything that
 * has to pick a light or dark variant reads this instead of asking the platform:
 * `isSystemInDarkTheme()` is blind to an explicit in-app choice, and on wasm it
 * always reports false, so a browser set to dark used to get light ink on the
 * dark palette wherever a component asked the system directly.
 */
val LocalIsDark = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The single rule behind [LocalIsDark]: an explicit choice wins, otherwise the
 * system, and on wasm the browser preference Compose cannot see. Kept apart from
 * the composable so the precedence is testable.
 */
internal fun resolveDark(themeMode: ThemeMode, systemDark: Boolean, webPrefersDark: Boolean?): Boolean =
    when (themeMode) {
        ThemeMode.SYSTEM -> systemDark || webPrefersDark == true
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Accent seed for the generated palette: user pick, dynamic color, or wallpaper. */
    seed: Color = DefaultSeed,
    content: @Composable () -> Unit,
) {
    val dark = resolveDark(themeMode, isSystemInDarkTheme(), prefersDarkSchemeWeb())
    // Tonal palettes fix every role's lightness, only hue/chroma follow the
    // seed - so text/surface contrast holds no matter which color is chosen.
    val scheme = remember(seed, dark) { schemeFromSeed(seed, dark) }
    CompositionLocalProvider(LocalIsDark provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Filled primary buttons. Dark mode uses a DEEP accent container with a white
 * label (the "blue button white text" pairing); light mode keeps the standard
 * scheme pairing. The container hue follows the active seed, so buttons stay
 * in the theme family no matter which accent is configured.
 */
@Composable
fun filledButtonColors(): ButtonColors {
    val scheme = MaterialTheme.colorScheme
    return if (LocalIsDark.current) {
        val hue = scheme.primary.oklchHue()
        val chroma = scheme.primary.oklchChroma()
        val container = oklchTone(hue, chroma, 42.0)
        ButtonDefaults.buttonColors(containerColor = container, contentColor = Color.White)
    } else {
        ButtonDefaults.buttonColors()
    }
}

/**
 * Browser dark-preference probe for wasmJs, where Compose's isSystemInDarkTheme()
 * always reports false (no platform hook). Returns null outside wasm/JS.
 */
@Composable
expect fun prefersDarkSchemeWeb(): Boolean?
