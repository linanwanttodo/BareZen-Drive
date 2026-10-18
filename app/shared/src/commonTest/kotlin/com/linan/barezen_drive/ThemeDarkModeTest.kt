package com.linan.barezen_drive

import com.linan.barezen_drive.ui.theme.ThemeMode
import com.linan.barezen_drive.ui.theme.resolveDark
import kotlin.test.*

/**
 * Which signal wins for "the app is dark". Components used to ask the platform
 * themselves - and on wasm `isSystemInDarkTheme()` is hardcoded false, so a
 * browser set to dark got light ink over the dark palette wherever a component
 * asked directly instead of reading what the theme resolved.
 */
class ThemeDarkModeTest {

    @Test
    fun explicitChoiceBeatsEverySystemSignal() {
        assertFalse(resolveDark(ThemeMode.LIGHT, systemDark = true, webPrefersDark = true))
        assertTrue(resolveDark(ThemeMode.DARK, systemDark = false, webPrefersDark = false))
    }

    @Test
    fun systemFollowsThePlatformItCanSee() {
        assertTrue(resolveDark(ThemeMode.SYSTEM, systemDark = true, webPrefersDark = null))
        assertFalse(resolveDark(ThemeMode.SYSTEM, systemDark = false, webPrefersDark = null))
    }

    @Test
    fun browserPreferenceCountsWhereThePlatformIsBlind() {
        // The wasm case: no OS hook, the media query is the only evidence.
        assertTrue(resolveDark(ThemeMode.SYSTEM, systemDark = false, webPrefersDark = true))
    }

    @Test
    fun anUnknownWebPreferenceFlipsNothing() {
        // null means "not a browser at all", which must not read as a preference.
        assertFalse(resolveDark(ThemeMode.SYSTEM, systemDark = false, webPrefersDark = false))
        assertTrue(resolveDark(ThemeMode.SYSTEM, systemDark = true, webPrefersDark = false))
    }
}
