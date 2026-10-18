package com.linan.barezen_drive.ui.screens.files

import com.linan.barezen_drive.core.dto.FileDto

/**
 * The rows to keep after a background refresh came back with the first page.
 *
 * A folder listing is ordered by name, so anything that arrived is interleaved
 * rather than prepended: the fresh page supplies the head, and the rows already
 * loaded *behind* that head keep their place. Rows the page covers but does not
 * list are dropped - inside its range, silence means the row is gone.
 *
 * "Behind the head" is the page's own boundary: the last row it returned, ordered
 * the way the server orders - `lower(name)` then id, which is the key the cursor
 * is cut from. Without that boundary a deleted file would sit on screen below the
 * head forever, because the page simply no longer mentions it.
 *
 * [complete] is false while a cursor is live, i.e. the user has pages loaded below
 * the head. With no cursor the listing fits on one page, the page is the whole
 * truth, and merging could only leave stale rows behind.
 */
internal fun mergeFirstPage(loaded: List<FileDto>, fresh: List<FileDto>, complete: Boolean): List<FileDto> {
    if (complete || fresh.isEmpty()) return fresh
    val last = fresh.last()
    val onThisPage = fresh.mapTo(HashSet()) { it.id }
    return fresh + loaded.filter { it.id !in onThisPage && sortsAfter(it, last) }
}

private fun sortsAfter(file: FileDto, boundary: FileDto): Boolean {
    val name = file.name.lowercase()
    val last = boundary.name.lowercase()
    return if (name != last) name > last else file.id > boundary.id
}
