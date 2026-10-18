package com.linan.barezen_drive.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The only corner-radius scale in the app.
 *
 * Before this existed the UI mixed Material's defaults with fourteen hand-written
 * radii (4 / 6 / 8 / 20dp), so two cards of the same kind could disagree by a few
 * points and nothing could be retuned in one place. Material's own steps are also
 * badly spaced for this app: 4-8-12-16 are close enough to read as the same corner
 * on small surfaces, while the jump to 28 makes the largest panel look like a
 * different product.
 *
 * These five steps are 4dp apart, so each one is a visible decision rather than
 * noise, and they sit a little softer than the defaults to match the frosted
 * panels the glass theme is built on.
 *
 * - [Shapes.extraSmall] decorative slivers: skeleton bars, breadcrumb chips.
 * - [Shapes.small] controls: buttons, fields' inner tiles, media thumbnails.
 * - [Shapes.medium] rows and inputs inside a card.
 * - [Shapes.large] cards and panels - the surfaces that carry content.
 * - [Shapes.extraLarge] hero surfaces: the search panel, the empty-state plate.
 *
 * Anything round by definition (avatars, status dots, progress tracks) keeps
 * `CircleShape`; it is a circle, not the top of this scale.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
