package com.linan.barezen_drive.ui.shell

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainShellUiTest {
    @Test
    fun keyboardVisibilityHidesBottomBar() {
        assertFalse(shouldShowTabBar(requested = true, imeVisible = true))
        assertTrue(shouldShowTabBar(requested = true, imeVisible = false))
        assertFalse(shouldShowTabBar(requested = false, imeVisible = false))
    }
}
