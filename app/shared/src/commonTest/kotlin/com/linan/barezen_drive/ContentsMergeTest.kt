package com.linan.barezen_drive

import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.ui.screens.files.mergeFirstPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A background refresh (an upload finishing anywhere bumps the global revision)
 * used to replace the whole listing with page one. For a user who had paged
 * deep, that is a yank: the rows they were looking at vanish and reappear only
 * after they scroll back down, and the folder they were reading is gone from the
 * screen for the duration of the request.
 *
 * The merge keeps what the user has loaded and lets the fresh page supply the
 * head - which has to mean both halves: a row the page dropped disappears, a row
 * the page simply never reaches survives.
 */
class ContentsMergeTest {
    private fun file(id: String, name: String = "f$id") = FileDto(
        id = id, name = name, folderId = null, size = 1L, mimeType = "text/plain",
        sha256 = id, createdAt = "t", updatedAt = "t",
    )

    private val loaded = listOf(file("a"), file("b"), file("c"), file("d"))
    private val complete = false

    @Test
    fun rowsThePageNeverReachesStayOnScreen() {
        // Page one came back with only `a` and `b`: `c` and `d` are page-two rows,
        // and their absence from page one says nothing about them.
        val merged = mergeFirstPage(loaded, listOf(file("a"), file("b")), complete)
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.id })
    }

    @Test
    fun aRowThePageDoesReachIsUpdatedInPlace() {
        val updated = file("b").copy(size = 99L)
        val merged = mergeFirstPage(loaded, listOf(file("a"), updated, file("c")), complete)
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.id })
        assertEquals(99L, merged[1].size, "the page's copy wins, so the change shows up")
        assertEquals(1, merged.count { it.id == "b" }, "and it is not duplicated behind itself")
    }

    @Test
    fun aRowThePageDropsLeavesTheListing() {
        // `a` was deleted from a folder whose first page now starts at `b`.
        val merged = mergeFirstPage(loaded, listOf(file("b"), file("c")), complete)
        assertTrue("a" !in merged.map { it.id }, "a row inside the page's range that is gone is gone: $merged")
        assertEquals(listOf("b", "c", "d"), merged.map { it.id })
    }

    @Test
    fun aListingThatFitsOnOnePageIsSimplyReplaced() {
        val short = listOf(file("a"), file("b"))
        // nextCursor was null: there is nothing below what is on screen, so the
        // page is the whole truth and merging could only leave stale rows behind.
        assertEquals(listOf("b"), mergeFirstPage(short, listOf(file("b")), complete = true).map { it.id })
    }
}
