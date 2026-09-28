package com.linan.barezen_drive

import com.linan.barezen_drive.data.transfer.TransferCenter
import com.linan.barezen_drive.data.transfer.TransferKind
import com.linan.barezen_drive.data.transfer.TransferLane
import com.linan.barezen_drive.data.transfer.TransferPhase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `TransferKind.DOWNLOAD` existed but nothing ever produced one: a download was
 * a black box from the moment the save dialog closed - no row, no percentage,
 * no notification, and a large file left the user tapping again because the app
 * looked stuck. The platform savers now report bytes as they copy, and this
 * pins the row's side of that contract: a download opens a row, moves, finishes
 * with its file id, and a failure is recorded instead of vanishing.
 */
class DownloadTransferTest {

    @Test
    fun aDownloadRowOpensMovesAndCompletes() = runTest {
        val id = TransferCenter.start("movie.mp4", TransferKind.DOWNLOAD, 8_000_000, TransferLane.FILE)

        val opened = TransferCenter.items.value.first { it.id == id }
        assertEquals(TransferKind.DOWNLOAD, opened.kind)
        assertEquals(TransferLane.FILE, opened.lane)
        assertEquals(0, opened.bytesDone)
        assertEquals(8_000_000, opened.bytesTotal)
        // A fresh row must read as in-flight, not finished: "working vs hung"
        // is the distinction the whole change is about.
        assertNotEquals(TransferPhase.DONE, opened.phase)

        TransferCenter.progress(id, 2_000_000, 8_000_000)
        val moving = TransferCenter.items.value.first { it.id == id }
        assertEquals(2_000_000, moving.bytesDone)
        assertNotEquals(TransferPhase.DONE, moving.phase, "an in-flight download must not read as done")

        TransferCenter.done(id, fileId = "file-42")
        val finished = TransferCenter.items.value.first { it.id == id }
        assertEquals(TransferPhase.DONE, finished.phase)
        assertEquals("file-42", finished.fileId, "the finished row points at the saved file")
    }

    @Test
    fun progressIsMonotonicUnlessARetryAsksToRestart() = runTest {
        val id = TransferCenter.start("clip.mp4", TransferKind.DOWNLOAD, 1000, TransferLane.FILE)
        TransferCenter.progress(id, 400, 1000)
        // A re-sent chunk must not walk the bar backwards; the row is the only
        // progress signal the user has.
        TransferCenter.progress(id, 300, 1000)
        assertEquals(
            400,
            TransferCenter.items.value.first { it.id == id }.bytesDone,
            "a smaller update must not rewind the row",
        )
        // A retry re-sends the file from byte zero, so it has to be able to say
        // so explicitly - otherwise the bar sits at 40% while nothing has been
        // sent yet.
        TransferCenter.progress(id, 0, 1000, reset = true)
        assertEquals(
            0,
            TransferCenter.items.value.first { it.id == id }.bytesDone,
            "an explicit reset must be honoured",
        )
    }

    @Test
    fun aDiscardedRowLeavesNothingBehind() = runTest {
        val id = TransferCenter.start("movie.mp4", TransferKind.DOWNLOAD, 1000, TransferLane.FILE)
        TransferCenter.discard(id)
        assertTrue(
            TransferCenter.items.value.none { it.id == id },
            "a save the user walked away from must not leave a row",
        )
        // And it must not resurrect a finished row either.
        val done = TransferCenter.start("done.mp4", TransferKind.DOWNLOAD, 10, TransferLane.FILE)
        TransferCenter.done(done)
        TransferCenter.discard(done)
        assertTrue(TransferCenter.items.value.any { it.id == done }, "a finished row stays until cleared")
    }

    @Test
    fun aFailedDownloadKeepsItsRowWithTheReason() = runTest {
        val id = TransferCenter.start("clip.mp4", TransferKind.DOWNLOAD, 1000, TransferLane.FILE)
        TransferCenter.progress(id, 400, 1000)
        TransferCenter.fail(id, "写入失败：存储已满")

        val row = TransferCenter.items.value.first { it.id == id }
        assertEquals(TransferPhase.FAILED, row.phase)
        assertTrue(row.error != null, "a failed download must keep its error")
        assertTrue(row.error!!.contains("存储已满"))
    }
}
