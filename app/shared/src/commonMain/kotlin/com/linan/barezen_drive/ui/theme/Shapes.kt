package com.linan.barezen_drive.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The only corner-radius scale in the app, and what each step means.
 *
 * Radii used to be written per call site (4 / 6 / 8 / 20dp alongside Material's
 * defaults), so two cards of the same kind could disagree by a few points and the
 * corner stopped carrying any information. Four semantic tiers, in increasing size:
 *
 * - [Shapes.extraSmall] 8dp - media. Thumbnails, photo tiles, skeleton blocks,
 *   breadcrumb chips: things whose own edge is the picture.
 * - [Shapes.small] 12dp - controls. Buttons, text fields, dropdowns, a grid cell
 *   around its media.
 * - [Shapes.medium] and [Shapes.large] 16dp - surfaces that carry content: cards,
 *   panels, grouped rows. The two slots are deliberately equal: Material has five
 *   slots and this app has four tiers, so a card reads the same whether it asked
 *   for `medium` or `large`. Do not use the difference between them as a visual
 *   knob; if a group ever needs its own step, give it a new named token instead.
 * - [Shapes.extraLarge] 24dp - dialogs and hero surfaces, the only places allowed
 *   to look softer than a card.
 *
 * Bottom-bar capsules and progress tracks are round by definition and keep
 * `CircleShape` / `Capsule`; they are not the top of this scale.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
