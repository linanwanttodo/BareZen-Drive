package com.linan.barezen_drive.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The hairline that stands in for elevation.
 *
 * Surfaces here are flat fills, and a flat fill on a flat background is the
 * "grey card floating on grey" problem: nothing says where one region stops.
 * A 1dp edge says it without introducing a shadow, which this UI does not use
 * anywhere - the alternative branch of the spec's "shadow or hairline, pick one
 * globally" rule.
 *
 * The color is the `outlineVariant` role, so it follows the seed and the lightness
 * tiers like every other color: no hex values, or a user-picked accent would stop
 * applying to the borders. [alpha] is only for surfaces that must read quieter
 * than a card; at 1 it matches what Material already uses for dividers, which is
 * the point - a divider and a card edge are the same line.
 */
@Composable
fun hairlineStroke(alpha: Float = 1f): BorderStroke =
    BorderStroke(HairlineWidth, hairlineColor(alpha))

/** [hairlineStroke] as a plain color, for `Modifier.border` on a background box. */
@Composable
fun hairlineColor(alpha: Float = 1f): Color =
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha)

/** One width for every hairline in the app. */
val HairlineWidth = 1.dp
