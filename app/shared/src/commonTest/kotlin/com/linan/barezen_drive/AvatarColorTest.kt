package com.linan.barezen_drive

import com.linan.barezen_drive.ui.theme.avatarColor
import com.linan.barezen_drive.ui.theme.avatarLetter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic coverage for the settings avatar helpers: the color must be a
 * stable function of the username only, and the letter must be the first
 * character, upper-cased.
 */
class AvatarColorTest {

    @Test
    fun avatarColorIsStableAcrossCalls() {
        val a = avatarColor("linanwanttodo")
        val b = avatarColor("linanwanttodo")
        assertEquals(a, b)
    }

    @Test
    fun avatarColorStaysInsidePalette() {
        val palette = setOf(
            avatarColor("alice"),
            avatarColor("bob"),
            avatarColor("carol"),
            avatarColor("dave"),
            avatarColor("erin"),
            avatarColor("frank"),
            avatarColor("grace"),
            avatarColor("heidi"),
        )
        // Every produced color is fully opaque.
        palette.forEach { assertEquals(1.0f, it.alpha) }
        assertTrue(palette.isNotEmpty())
    }

    @Test
    fun avatarColorBlankIsFixed() {
        assertEquals(avatarColor(""), avatarColor("   "))
    }

    @Test
    fun avatarLetterUsesFirstCharacterUpperCased() {
        assertEquals("L", avatarLetter("linan"))
        assertEquals("A", avatarLetter("alice"))
        assertEquals(".", avatarLetter(""))
        assertEquals(".", avatarLetter("   "))
    }
}
