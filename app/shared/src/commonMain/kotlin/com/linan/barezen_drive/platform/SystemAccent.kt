package com.linan.barezen_drive.platform

import androidx.compose.ui.graphics.Color

/**
 * The system's own accent color (Android 12+: derived from the user's
 * wallpaper, like Material You). Null when the platform cannot provide one -
 * callers should hide the dynamic-color option then.
 */
expect fun systemAccentColor(): Color?
