package com.linan.barezen_drive.data.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide "the library changed on the server" ticker.
 *
 * Uploads finish wherever the work happens - the file uploader, a manual pick
 * on the album tab, or an album backup running inside a WorkManager job while
 * the UI sits on some other screen - and none of those paths can reach into a
 * screen to tell it that its list is stale. Screens that show server content
 * observe this counter and re-read when it moves.
 *
 * Before this existed, every list loaded exactly once: a backup that finished
 * while the user was looking at the album (or at the home tab's recent strip)
 * left the new photos invisible until they navigated away and back, which read
 * as "the upload said it worked but nothing appeared".
 */
object LibraryRevision {
    private val _value = MutableStateFlow(0L)

    /** Monotonic; screens only care that it moved, never by how much. */
    val value: StateFlow<Long> = _value.asStateFlow()

    /**
     * Called when a file has actually landed on the server. Callers must have a
     * server-assigned id in hand: a bump makes every observer re-read, so it is
     * only worth paying for real content, not for a queued or failed transfer.
     */
    fun bump() {
        _value.value += 1L
    }
}
