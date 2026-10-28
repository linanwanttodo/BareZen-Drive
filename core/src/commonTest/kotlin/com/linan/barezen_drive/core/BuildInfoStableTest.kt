package com.linan.barezen_drive.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The unattended upgrade path accepts plain x.y.z only, so the pre-release forms
 * a beta build carries must be rejected here as well as by GitHub's
 * releases/latest endpoint.
 */
class BuildInfoStableTest {

    @Test
    fun plainThreePartVersionsAreStable() {
        assertEquals(true, BuildInfo.isStable("0.2.1"))
        assertEquals(true, BuildInfo.isStable("v1.10.0"))
        assertEquals(true, BuildInfo.isStable("10.0.3"))
    }

    @Test
    fun preReleaseAndBuildSuffixesAreNotStable() {
        assertEquals(false, BuildInfo.isStable("0.3.0-beta.1"))
        assertEquals(false, BuildInfo.isStable("0.3.0-rc1"))
        assertEquals(false, BuildInfo.isStable("0.3.0+build7"))
    }

    @Test
    fun malformedVersionsAreNotStable() {
        assertEquals(false, BuildInfo.isStable(""))
        assertEquals(false, BuildInfo.isStable("0.3"))
        assertEquals(false, BuildInfo.isStable("0.3.0.1"))
        assertEquals(false, BuildInfo.isStable("0..3"))
        assertEquals(false, BuildInfo.isStable("latest"))
    }
}
