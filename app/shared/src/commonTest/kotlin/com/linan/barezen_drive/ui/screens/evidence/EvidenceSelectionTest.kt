package com.linan.barezen_drive.ui.screens.evidence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvidenceSelectionTest {
    @Test
    fun firstSelectionActivatesSelectionMode() {
        val selection = EvidenceSelection().toggle("segment-1")
        assertTrue(selection.active)
        assertEquals(setOf("segment-1"), selection.selectedKeys)
    }

    @Test
    fun cloudRestoreDoesNotRequirePasswordConfirmation() {
        assertTrue(canSubmitEvidencePassword("password-123", creating = true, restoringCloud = true, confirmation = ""))
        assertFalse(canSubmitEvidencePassword("password-123", creating = true, restoringCloud = false, confirmation = ""))
        assertTrue(canSubmitEvidencePassword("password-123", creating = true, restoringCloud = false, confirmation = "password-123"))
    }

    @Test
    fun selectionCanToggleAllAndClearWithoutLeavingModeAccidentally() {
        val active = EvidenceSelection().toggle("segment-1")
        val all = active.selectAll(listOf("segment-1", "segment-2"))
        assertEquals(setOf("segment-1", "segment-2"), all.selectedKeys)
        assertTrue(all.active)
        assertFalse(all.toggle("segment-1").selectedKeys.contains("segment-1"))
        assertFalse(all.clear().active)
        assertTrue(all.clear().selectedKeys.isEmpty())
    }
}
