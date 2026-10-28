package com.linan.barezen_drive.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mirror fail-over and the stage-then-install composition of the Android
 * update path, extracted as pure functions so their preserved semantics are
 * pinned without a network stack or a device: downloadAndInstallUpdate and
 * stageUpdateApk are thin shells over exactly these two rules.
 */
class UpdateStagingTest {

    // --- stageWithMirrors -------------------------------------------------

    @Test
    fun mirrorsAreTriedInListOrder() {
        val tried = mutableListOf<String>()
        val got = stageWithMirrors(listOf("a", "b", "c")) { url ->
            tried.add(url)
            null
        }
        assertNull(got)
        assertEquals(listOf("a", "b", "c"), tried)
    }

    @Test
    fun theFirstSuccessWinsAndStopsTheSweep() {
        val tried = mutableListOf<String>()
        val got = stageWithMirrors(listOf("a", "b", "c")) { url ->
            tried.add(url)
            if (url == "b") "/storage/updates/b.apk" else null
        }
        assertEquals("/storage/updates/b.apk", got)
        assertEquals(listOf("a", "b"), tried)
    }

    @Test
    fun aThrowingMirrorFailsOverToTheNext() {
        val got = stageWithMirrors(listOf("a", "b")) { url ->
            if (url == "a") error("connection reset") else "/storage/updates/b.apk"
        }
        assertEquals("/storage/updates/b.apk", got)
    }

    @Test
    fun everyMirrorFailingMeansNothingStaged() {
        val got = stageWithMirrors(listOf("a", "b")) { url ->
            if (url == "a") error("timeout") else null
        }
        assertNull(got)
    }

    @Test
    fun anEmptyMirrorListStagesNothing() {
        var calls = 0
        val got = stageWithMirrors(emptyList()) {
            calls++
            null
        }
        assertNull(got)
        assertEquals(0, calls)
    }

    // --- installAndAccept --------------------------------------------------

    @Test
    fun nothingStagedIsAFailureAndNeverInstalls() {
        var calls = 0
        val got = installAndAccept(null) {
            calls++
        }
        assertEquals(false, got)
        assertEquals(0, calls)
    }

    @Test
    fun anInstallerThatThrowsStillCountsAsSuccess() {
        // The verified bytes are on disk; a rejected installer intent must not
        // be reported as a download failure.
        val got = installAndAccept("/storage/updates/app.apk") {
            throw IllegalStateException("installer intent rejected")
        }
        assertTrue(got)
    }

    @Test
    fun aCleanInstallSucceedsWithTheStagedPath() {
        var installed: String? = null
        val got = installAndAccept("/storage/updates/app.apk") { installed = it }
        assertTrue(got)
        assertEquals("/storage/updates/app.apk", installed)
    }
}
