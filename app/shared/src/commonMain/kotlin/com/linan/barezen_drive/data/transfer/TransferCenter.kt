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
        // System notification too: netdisks show progress in the shade so the
        // user can leave the app.
        runCatching {
            com.linan.barezen_drive.platform.TransferNotifier.showProgress(
                tag = id,
                title = name,
                text = null,
                fraction = if (total > 0) 0f else null,
            )
        }
        return id
    }

    fun progress(id: String, done: Long, total: Long) {
        var title = ""
        var fraction: Float? = null
        update(id) {
            val t = if (total > 0) total else it.bytesTotal
            title = it.name
            fraction = if (t > 0) (done.toFloat() / t).coerceIn(0f, 1f) else null
            it.copy(bytesDone = done, bytesTotal = t)
        }
        runCatching { com.linan.barezen_drive.platform.TransferNotifier.showProgress(id, title, null, fraction) }
    }

    fun done(id: String) {
        val name = _items.value.firstOrNull { it.id == id }?.name ?: return
        update(id) { it.copy(phase = TransferPhase.DONE, atMs = nowMs()) }
        runCatching { com.linan.barezen_drive.platform.TransferNotifier.showFinished(id, name, "Done", ok = true) }
    }

    fun fail(id: String, error: String) {
        val name = _items.value.firstOrNull { it.id == id }?.name ?: return
        update(id) { it.copy(phase = TransferPhase.FAILED, error = error, atMs = nowMs()) }
        runCatching { com.linan.barezen_drive.platform.TransferNotifier.showFinished(id, name, error, ok = false) }
    }

    /** Removes finished rows (the "clear" action on the done tab). */
    fun clearFinished() {
        val finished = _items.value.filter { it.phase != TransferPhase.RUNNING }
        _items.update { list -> list.filter { it.phase == TransferPhase.RUNNING } }
        finished.forEach { runCatching { com.linan.barezen_drive.platform.TransferNotifier.dismiss(it.id) } }
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
