package com.linan.barezen_drive.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's type scale, on top of the Material 3 defaults.
 *
 * Only the roles this UI actually uses are overridden; the display roles and the
 * remaining headline/title slots keep Material's values. Before this the app ran
 * on the stock scale, and the stock scale is built for Latin text at a desktop
 * reading distance: the metadata line (file size and date, 56 call sites) landed
 * on 12sp, which is the smallest thing on a phone screen that is still meant to
 * be read, and the weights never separated a section header from the text under
 * it.
 *
 * Three tiers carry the hierarchy, matching the weights the interface is specced
 * at: headers 600, body 400, labels 500.
 *
 * - 20sp page title (the app bar) / 18sp dialog title
 * - 16sp section header
 * - 14sp body - also the default for a `Text` with no style, so it stays put
 * - 13sp metadata, the second line of a row
 * - 12sp and 11sp labels
 *
 * `letterSpacing` is zeroed on every role here. Material's positive tracking
 * (0.25-0.5sp) exists to keep lowercase Latin from crowding; on the Chinese
 * strings this app is mostly made of it pushes each glyph apart and the line
 * reads as a row of dots instead of a word.
 *
 * `fontFeatureSettings = "tnum"` asks the font for tabular figures so digits in
 * a column (sizes, dates, latencies, percentages) align instead of jittering.
 * It is a request, not a guarantee: a font without tabular glyphs ignores it,
 * and the wasm canvas backend may not apply it at all.
 */
private val Base = Typography()

val AppTypography = Typography(
    // AlertDialog titles resolve through this slot, which is why the dialog tier
    // lives here and not in a dialog-specific style.
    headlineSmall = Base.headlineSmall.copy(
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    headlineMedium = Base.headlineMedium.copy(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = Base.titleLarge.copy(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = Base.titleMedium.copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = Base.titleSmall.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = Base.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    ),
    bodyMedium = Base.bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    ),
    bodySmall = Base.bodySmall.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    ),
    labelLarge = Base.labelLarge.copy(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    ),
    labelMedium = Base.labelMedium.copy(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    ),
    labelSmall = Base.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    ),
)
