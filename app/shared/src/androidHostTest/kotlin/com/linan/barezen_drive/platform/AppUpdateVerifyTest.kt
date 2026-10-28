package com.linan.barezen_drive.platform

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The verify-and-discard rule for a downloaded package, pinned against real
 * temporary files: bytes that do not match the manifest must be deleted (a
 * bad file left on disk can reach a later install attempt), bytes that match
 * must survive for the installer, and manifest fields that are absent are
 * skipped rather than failed. The staged-path contract of [pathIfVerified]
 * is pinned here too: an unverified file is never returned as a path, and
 * the closing 1f goes out only after verification passes, exactly once.
 * Sha256er runs on the host JVM, as Sha256Test already proves, so the digest
 * branch needs no Android context.
 */
class AppUpdateVerifyTest {

    private val content = "fake-apk-payload-bytes".encodeToByteArray()
    private val created = mutableListOf<File>()

    private fun stagedFile(bytes: ByteArray = content): File =
        File.createTempFile("barezen-update-", ".apk").also {
            created.add(it)
            it.writeBytes(bytes)
        }

    private fun sha256Of(bytes: ByteArray): String {
        val hasher = Sha256er.newInstance()
        hasher.update(bytes)
        return hasher.digestHex()
    }

    @AfterTest
    fun cleanUp() {
        created.forEach { it.delete() }
        created.clear()
    }

    @Test
    fun matchingDigestAndSizeKeepTheFile() {
        val file = stagedFile()
        assertTrue(verifyDownloaded(file, sha256Of(content), content.size.toLong()))
        assertTrue(verifyOrDiscard(file, sha256Of(content), content.size.toLong()))
        assertTrue(file.isFile)
    }

    @Test
    fun aWrongDigestDeletesTheFile() {
        val file = stagedFile()
        assertFalse(verifyOrDiscard(file, "f".repeat(64), content.size.toLong()))
        assertEquals(false, file.exists())
    }

    @Test
    fun aWrongSizeDeletesTheFileEvenWithTheRightDigest() {
        val file = stagedFile()
        assertFalse(verifyOrDiscard(file, sha256Of(content), content.size.toLong() + 1))
        assertEquals(false, file.exists())
    }

    @Test
    fun aMissingFileFailsAndNothingIsThrown() {
        val missing = File("barezen-verify-no-such-file.apk")
        assertFalse(verifyDownloaded(missing, null, null))
        assertFalse(verifyOrDiscard(missing, null, null))
    }

    @Test
    fun manifestFieldsThatAreAbsentAreSkippedNotFailed() {
        // No digest, no size: an older manifest still updates.
        val file = stagedFile()
        assertTrue(verifyOrDiscard(file, null, null))
        assertTrue(file.isFile)

        // Size present, digest absent.
        val sizeOnly = stagedFile()
        assertTrue(verifyOrDiscard(sizeOnly, null, sizeOnly.length()))
        assertTrue(sizeOnly.isFile)

        // Digest present in upper case, size absent: the comparison is
        // case-insensitive because manifests and hex dumps disagree on case.
        val upperCase = stagedFile()
        assertTrue(verifyOrDiscard(upperCase, sha256Of(content).uppercase(), null))
        assertTrue(upperCase.isFile)

        // Blank digest counts as absent.
        val blank = stagedFile()
        assertTrue(verifyOrDiscard(blank, "  ", null))
        assertTrue(blank.isFile)
    }

    @Test
    fun aFailedVerifyReturnsNoPathAndNeverReportsDone() {
        val progress = mutableListOf<Float>()
        val file = stagedFile()
        assertNull(pathIfVerified(file, "f".repeat(64), content.size.toLong()) { progress.add(it) })
        assertFalse(progress.contains(1f))
        assertEquals(false, file.exists())
    }

    @Test
    fun aVerifiedFileIsTheStagedPathAnd1fGoesOutExactlyOnce() {
        val progress = mutableListOf<Float>()
        val file = stagedFile()
        val path = pathIfVerified(file, sha256Of(content), content.size.toLong()) { progress.add(it) }
        assertEquals(file.absolutePath, path)
        assertEquals(listOf(1f), progress)
        assertTrue(file.isFile)
    }
}
