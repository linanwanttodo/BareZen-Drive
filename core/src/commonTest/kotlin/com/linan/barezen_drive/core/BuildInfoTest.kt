package com.linan.barezen_drive.core

import kotlin.test.*

class BuildInfoTest {

    @Test
    fun normalizeStripsVersionPrefix() {
        assertEquals("1.2.3", BuildInfo.normalize("v1.2.3"))
        assertEquals("1.2.3", BuildInfo.normalize("V1.2.3"))
        assertEquals("1.2.3", BuildInfo.normalize(" 1.2.3 "))
    }

    @Test
    fun isNewerComparesEachSegment() {
        assertTrue(BuildInfo.isNewer("0.0.2", "0.0.1"))
        assertTrue(BuildInfo.isNewer("0.1.0", "0.0.9"))
        assertTrue(BuildInfo.isNewer("1.0.0", "0.99.99"))
        assertTrue(BuildInfo.isNewer("v0.0.2", "0.0.1"))
        assertFalse(BuildInfo.isNewer("0.0.1", "0.0.1"))
        assertFalse(BuildInfo.isNewer("0.0.1", "0.0.2"))
    }

    @Test
    fun isNewerTreatsMissingSegmentsAsZero() {
        assertFalse(BuildInfo.isNewer("1.2", "1.2.0"))
        assertTrue(BuildInfo.isNewer("1.2.1", "1.2"))
    }

    @Test
    fun isNewerIgnoresPrereleaseSuffix() {
        // Suffixes are not ordered; only the numeric core is compared.
        assertFalse(BuildInfo.isNewer("1.0.0-rc1", "1.0.0"))
        assertTrue(BuildInfo.isNewer("1.0.1-rc1", "1.0.0"))
    }
}
