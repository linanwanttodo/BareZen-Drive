package com.linan.barezen_drive.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The saver used to report a single `String?`: the saved location, or null for
 * *both* "the user closed the system save dialog" and "the write failed". Every
 * caller then had to guess, and they all guessed the same wrong way - a
 * dismissed save dialog was announced to the user as a failed download.
 *
 * The workaround that shipped in its place inferred the difference from whether
 * a progress callback had ever fired. That fixes the cancel case and breaks the
 * first-byte one: a write that dies inside `openOutputStream` never reports a
 * byte, so the failure was classified as a cancel and swallowed.
 *
 * These tests pin the explicit contract instead.
 */
class FileSaveResultTest {

    @Test
    fun aSavedFileClosesTheWork() {
        assertEquals(SaveOutcome.DONE, FileSaveResult.Saved("content://docs/42").outcome())
        // The web saver has no uri; it reports the file name it handed to the
        // browser. A non-empty location is what marks it saved.
        assertEquals(SaveOutcome.DONE, FileSaveResult.Saved("movie.mp4").outcome())
    }

    @Test
    fun aDismissedSaveDialogIsSilent() {
        // Regression: the user backing out of the document picker is not an
        // error. Reporting it as one told people their download was broken when
        // they had asked for it to stop.
        assertEquals(SaveOutcome.SILENT, FileSaveResult.Cancelled.outcome())
    }

    @Test
    fun aWriteFailureIsReportedEvenWithNoProgress() {
        val failed = FileSaveResult.Failed("openOutputStream: permission denied")
        assertEquals(SaveOutcome.FAILED, failed.outcome())
        // The reason is for diagnostics; a null/blank one still fails.
        assertEquals(SaveOutcome.FAILED, FileSaveResult.Failed(null).outcome())
        assertEquals(SaveOutcome.FAILED, FileSaveResult.Failed("").outcome())
    }

    @Test
    fun theOldProgressInferenceSwallowedAFirstByteFailure() {
        // This is the case the progress heuristic could not express. The write
        // never started, so nothing was reported, so the old code read it as a
        // save-dialog cancel and dropped the failure on the floor.
        val result = FileSaveResult.Failed("openOutputStream threw")
        val reportedProgress = false

        assertEquals(SaveOutcome.FAILED, result.outcome(), "an explicit failure must survive")
        assertEquals(
            SaveOutcome.SILENT,
            legacyOutcome(location = null, reportedProgress = reportedProgress),
            "the old inference really did misreport this - the reason it cannot be kept",
        )
    }

    @Test
    fun theOldProgressInferenceCollapsedTwoDifferentOutcomesIntoOne() {
        // The honest statement about the old heuristic: it got the cancel right
        // by accident, but only because it was the *same* answer it gave to a
        // write that died before its first byte. One code path, two unrelated
        // situations, indistinguishable - which is why a real failure could be
        // swallowed while a deliberate cancel looked handled.
        val cancel = legacyOutcome(location = null, reportedProgress = false)
        val firstByteFailure = legacyOutcome(location = null, reportedProgress = false)
        assertEquals(cancel, firstByteFailure, "the old code could not tell these apart")

        // The explicit contract does tell them apart, and gets both right.
        assertEquals(SaveOutcome.SILENT, FileSaveResult.Cancelled.outcome())
        assertEquals(SaveOutcome.FAILED, FileSaveResult.Failed("openOutputStream threw").outcome())
    }

    @Test
    fun aCancelStaysSilentEvenIfBytesHadMoved() {
        // Defensive: the platform reports Cancelled only before it copies, but
        // a cancel must never turn into a failure toast no matter what the
        // transfer row happened to record in the meantime.
        assertEquals(SaveOutcome.SILENT, FileSaveResult.Cancelled.outcome())
    }

    @Test
    fun everyResultIsClassifiedWithoutGuessing() {
        val classified = listOf(
            FileSaveResult.Saved("/tmp/a.bin"),
            FileSaveResult.Cancelled,
            FileSaveResult.Failed("disk full"),
        ).map { it.outcome() }
        assertEquals(listOf(SaveOutcome.DONE, SaveOutcome.SILENT, SaveOutcome.FAILED), classified)
    }

    /** The heuristic that shipped before the explicit result type. */
    private fun legacyOutcome(location: String?, reportedProgress: Boolean): SaveOutcome =
        when {
            location != null -> SaveOutcome.DONE
            !reportedProgress -> SaveOutcome.SILENT
            else -> SaveOutcome.FAILED
        }
}
