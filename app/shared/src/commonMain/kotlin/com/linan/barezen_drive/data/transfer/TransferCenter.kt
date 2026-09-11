package com.linan.barezen_drive.data.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What a transfer row represents. */
enum class TransferKind { UPLOAD, DOWNLOAD, SYNC }

enum class TransferPhase { RUNNING, DONE, FAILED }

data class TransferItem(
    val id: String,
    val name: String,
    val kind: TransferKind,
    val phase: TransferPhase,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val error: String? = null,
    val atMs: Long = 0,
)

/**
 * In-memory transfer registry behind the transfer-centre screen.
 *
 * Uploads report here through UploadManager; the album "sync now" action and
 * downloads report here too. Entries are kept for the session (a durable
 * history is a later increment) and exposed as one StateFlow so the three
 * tabs - active uploads, active downloads, finished - are a pure projection.
 */
object TransferCenter {
    private const val MAX_HISTORY = 100

    private val _items = MutableStateFlow<List<TransferItem>>(emptyList())
    val items: StateFlow<List<TransferItem>> = _items

    private var seq = 0L

    fun start(name: String, kind: TransferKind, total: Long): String {
        val id = "t${++seq}"
        push(TransferItem(id, name, kind, TransferPhase.RUNNING, 0, total, atMs = nowMs()))
        return id
    }

    fun progress(id: String, done: Long, total: Long) {
        update(id) { it.copy(bytesDone = done, bytesTotal = if (total > 0) total else it.bytesTotal) }
    }

    fun done(id: String) = update(id) { it.copy(phase = TransferPhase.DONE, atMs = nowMs()) }

    fun fail(id: String, error: String) =
        update(id) { it.copy(phase = TransferPhase.FAILED, error = error, atMs = nowMs()) }

    /** Removes finished rows (the "clear" action on the done tab). */
    fun clearFinished() {
        _items.update { list -> list.filter { it.phase == TransferPhase.RUNNING } }
    }

    private fun push(item: TransferItem) {
        _items.update { (listOf(item) + it).take(MAX_HISTORY) }
    }

    private fun update(id: String, block: (TransferItem) -> TransferItem) {
        _items.update { list -> list.map { if (it.id == id) block(it) else it } }
    }

    // A plain counter, not a clock: the rows only need creation ORDER, and a
    // platform clock would drag Android framework calls into unit tests.
    private fun nowMs(): Long = ++seq
}
