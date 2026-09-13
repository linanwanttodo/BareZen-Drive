package com.linan.barezen_drive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the queue's decision rules.
 *
 * These live in `commonTest` rather than next to the Android code on purpose:
 * `SyncDb` / `SyncScan` are built on SQLiteOpenHelper, Cursor and MediaStore, and
 * the project's `androidHostTest` source set is a plain JVM test with no
 * Robolectric, so anything touching those types cannot be executed here. Keeping
 * the policy free of Android types is what makes it verifiable at all.
 */
class SyncPolicyTest {

    // ---- album categorisation ----

    @Test
    fun albumCategoryPassesOrdinaryAlbumNames() {
        assertEquals("Camera", SyncPolicy.albumCategory("Camera"))
        assertEquals("WeChat", SyncPolicy.albumCategory("WeChat"))
        assertEquals("Screenshots", SyncPolicy.albumCategory("Screenshots"))
    }

    @Test
    fun albumCategoryRejectsHiddenAndCacheAlbums() {
        assertNull(SyncPolicy.albumCategory(null))
        assertNull(SyncPolicy.albumCategory(""))
        assertNull(SyncPolicy.albumCategory("   "))
        assertNull(SyncPolicy.albumCategory(".thumbnails"))
        // WeChat-style content hash bucket.
        assertNull(SyncPolicy.albumCategory("0123456789abcdef"))
    }

    @Test
    fun albumCategoryBoundaryIsSixteenHexChars() {
        // 15 hex characters are still a plausible album name; 16 seal it.
        assertEquals("0123456789abcde", SyncPolicy.albumCategory("0123456789abcde"))
        assertNull(SyncPolicy.albumCategory("0123456789abcdef"))
    }

    // ---- stale detection ----

    @Test
    fun onlyDoneRowsCanBecomeStale() {
        assertTrue(
            SyncPolicy.isStale(SyncState.DONE, storedSize = 10, storedDateModified = 1, scannedSize = 11, scannedDateModified = 1),
        )
        assertFalse(
            SyncPolicy.isStale(SyncState.PENDING, storedSize = 10, storedDateModified = 1, scannedSize = 11, scannedDateModified = 2),
        )
        assertFalse(
            SyncPolicy.isStale(SyncState.UPLOADING, storedSize = 10, storedDateModified = 1, scannedSize = 11, scannedDateModified = 2),
        )
        assertFalse(
            SyncPolicy.isStale(SyncState.FAILED, storedSize = 10, storedDateModified = 1, scannedSize = 11, scannedDateModified = 2),
        )
    }

    @Test
    fun staleTracksSizeAndModificationTimeIndependently() {
        // Same size, edited in place: the timestamp is what catches it.
        assertTrue(
            SyncPolicy.isStale(SyncState.DONE, storedSize = 10, storedDateModified = 1, scannedSize = 10, scannedDateModified = 2),
        )
        // Same timestamp, different size: caught by the size column.
        assertTrue(
            SyncPolicy.isStale(SyncState.DONE, storedSize = 10, storedDateModified = 1, scannedSize = 12, scannedDateModified = 1),
        )
        assertFalse(
            SyncPolicy.isStale(SyncState.DONE, storedSize = 10, storedDateModified = 1, scannedSize = 10, scannedDateModified = 1),
        )
    }

    // ---- due selection ----

    @Test
    fun pendingIsAlwaysDueAndDoneNeverIs() {
        assertTrue(SyncPolicy.isDue(SyncState.PENDING, attempts = 0))
        assertTrue(SyncPolicy.isDue(SyncState.PENDING, attempts = 99, maxAttempts = 5))
        assertFalse(SyncPolicy.isDue(SyncState.DONE, attempts = 0))
        // UPLOADING rows are owned by a live lane, not by the next pass.
        assertFalse(SyncPolicy.isDue(SyncState.UPLOADING, attempts = 0))
    }

    @Test
    fun failedIsDueOnlyUnderTheRetryCeiling() {
        assertTrue(SyncPolicy.isDue(SyncState.FAILED, attempts = 4, maxAttempts = 5))
        assertFalse(SyncPolicy.isDue(SyncState.FAILED, attempts = 5, maxAttempts = 5))
        assertFalse(SyncPolicy.isDue(SyncState.FAILED, attempts = 6, maxAttempts = 5))
        // The default ceiling is the one the drain loop and the counters share.
        assertTrue(SyncPolicy.isDue(SyncState.FAILED, attempts = SyncPolicy.MAX_ATTEMPTS - 1))
        assertFalse(SyncPolicy.isDue(SyncState.FAILED, attempts = SyncPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun dueOrderPutsPhotosBeforeVideosThenOldestFirst() {
        val order = SyncPolicy.dueOrder()
        assertTrue(order.startsWith("is_photo DESC"), order)
        assertTrue(order.contains("date_modified ASC"), order)
    }

    // ---- concurrency by link type ----

    @Test
    fun unmeteredLinksGetTwoLanesAndMeteredLinksGetOne() {
        assertEquals(2, SyncPolicy.lanesFor(unmetered = true))
        assertEquals(1, SyncPolicy.lanesFor(unmetered = false))
        // Two concurrent 20 MiB chunk streams are what make a phone's mobile
        // data unusable for everything else on it.
        assertEquals(SyncPolicy.LANES_UNMETERED, SyncPolicy.lanesFor(true))
        assertEquals(SyncPolicy.LANES_METERED, SyncPolicy.lanesFor(false))
    }

    @Test
    fun laneCountIsAlwaysAtLeastOne() {
        // Guards the drain loop: repeat(0) would start no lanes and the pass
        // would return "nothing done" while the queue is still full.
        assertTrue(SyncPolicy.lanesFor(true) >= 1)
        assertTrue(SyncPolicy.lanesFor(false) >= 1)
    }

    // ---- transport failure classification ----

    @Test
    fun transportFailuresAreRecognised() {
        assertTrue(SyncPolicy.isNetworkFailure("Failed to connect to /10.0.2.2:8080"))
        assertTrue(SyncPolicy.isNetworkFailure("Unable to resolve host \"nas.local\""))
        assertTrue(SyncPolicy.isNetworkFailure("Network is unreachable"))
        assertTrue(SyncPolicy.isNetworkFailure("Request timeout"))
        assertTrue(SyncPolicy.isNetworkFailure("Connection timed out"))
        assertTrue(SyncPolicy.isNetworkFailure("Connection reset by peer"))
        assertTrue(SyncPolicy.isNetworkFailure("Broken pipe"))
        assertTrue(SyncPolicy.isNetworkFailure("Connection refused"))
        assertTrue(SyncPolicy.isNetworkFailure("Software caused connection abort"))
        // Case-insensitive: the messages come from several layers.
        assertTrue(SyncPolicy.isNetworkFailure("FAILED TO CONNECT"))
    }

    @Test
    fun serverRejectionsAreNotTransportFailures() {
        // These must consume the item's retry budget, not stop the whole pass:
        // retrying a 409 for ever is exactly the battery waste this prevents.
        assertFalse(SyncPolicy.isNetworkFailure("HTTP 409 Conflict"))
        assertFalse(SyncPolicy.isNetworkFailure("NAME_CONFLICT"))
        assertFalse(SyncPolicy.isNetworkFailure("500 Internal Server Error"))
        assertFalse(SyncPolicy.isNetworkFailure("QUOTA_EXCEEDED"))
        assertFalse(SyncPolicy.isNetworkFailure(null))
        assertFalse(SyncPolicy.isNetworkFailure(""))
    }

    @Test
    fun twoConsecutiveTransportFailuresStopThePass() {
        assertFalse(SyncPolicy.shouldStopPass(0))
        assertFalse(SyncPolicy.shouldStopPass(1))
        assertTrue(SyncPolicy.shouldStopPass(2))
        assertTrue(SyncPolicy.shouldStopPass(SyncPolicy.NETWORK_FAILURE_LIMIT))
        assertTrue(SyncPolicy.shouldStopPass(9))
    }

    // ---- incremental scan watermark ----

    @Test
    fun missingWatermarkMeansFullScan() {
        assertNull(SyncPolicy.scanSinceSeconds(null))
        assertNull(SyncPolicy.scanSinceSeconds(0L))
        // A clock that somehow went backwards must not produce a future cut-off.
        assertNull(SyncPolicy.scanSinceSeconds(-1L))
    }

    @Test
    fun watermarkIsConvertedToSecondsAndBackedOffByTheOverlap() {
        // 1_000_000 ms = 1000 s; minus the 300 s overlap = 700 s.
        assertEquals(700L, SyncPolicy.scanSinceSeconds(1_000_000L))
        // MediaStore stores DATE_MODIFIED in seconds.
        assertEquals(
            (1_000_000L / 1000L) - SyncPolicy.SCAN_OVERLAP_SECONDS,
            SyncPolicy.scanSinceSeconds(1_000_000L),
        )
    }

    @Test
    fun watermarkNearTheEpochClampsAtZero() {
        // 100 s minus a 300 s overlap would go negative; a negative selection
        // argument would match nothing on some providers.
        assertEquals(0L, SyncPolicy.scanSinceSeconds(100_000L))
    }

    // ---- state keys ----

    @Test
    fun stateKeysRoundTripAndUnknownValuesFallBackToPending() {
        SyncState.entries.forEach { assertEquals(it, SyncState.fromKey(it.key)) }
        // A row written by a newer build, or a corrupted value, must be retried
        // rather than stranded.
        assertEquals(SyncState.PENDING, SyncState.fromKey("something-new"))
        assertEquals(SyncState.PENDING, SyncState.fromKey(""))
    }
}
