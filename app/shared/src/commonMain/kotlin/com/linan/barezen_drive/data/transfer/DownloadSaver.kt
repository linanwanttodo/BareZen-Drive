package com.linan.barezen_drive.data.transfer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.platform.FileSaveRequest
import com.linan.barezen_drive.platform.FileSaveResult
import com.linan.barezen_drive.platform.SaveOutcome
import com.linan.barezen_drive.platform.outcome
import com.linan.barezen_drive.platform.rememberFileSaver
import kotlinx.coroutines.launch

/**
 * Downloads as a first-class transfer.
 *
 * A download used to vanish the moment the save dialog closed: no row, no
 * percentage, no notification. On a large file over a phone link that is
 * minutes of nothing, and the only rational reaction is to tap download again.
 * `TransferKind.DOWNLOAD` existed for this and nothing ever produced one.
 *
 * Each request opens its row before the platform starts copying; the platform
 * reports bytes as it writes them, and the row is closed here - done on success,
 * failed with the reason on error, removed when the user backed out of the save
 * dialog (nothing was copied, so there is nothing to report).
 *
 * The row's fate comes from the saver's explicit result, not from whether a
 * progress callback happened to fire. Inferring it that way cost us a real
 * failure: a write that died before its first byte reported no bytes and was
 * therefore discarded as a cancel.
 *
 * @param onFailed invoked only for a genuinely failed save (never for a
 *   cancelled one) so the screen can show its own wording; the row already
 *   carries the detail.
 */
@Composable
fun rememberDownloadSaver(
    repo: FilesRepository,
    onFailed: suspend () -> Unit,
): (FileDto) -> Unit {
    val scope = rememberCoroutineScope()
    // file id -> transfer row id, so a completion closes its own row.
    val rows = remember { mutableMapOf<String, String>() }

    val saver = rememberFileSaver(
        onDone = { req: FileSaveRequest, result: FileSaveResult ->
            val row = rows.remove(req.id)
            when (result.outcome()) {
                SaveOutcome.DONE -> row?.let { TransferCenter.done(it, fileId = req.id) }
                // The user left the save dialog. Not an error, and definitely not
                // a toast about a file that never started downloading.
                SaveOutcome.SILENT -> row?.let { TransferCenter.discard(it) }
                SaveOutcome.FAILED -> {
                    row?.let { TransferCenter.fail(it, I18n.strings.downloadFailed) }
                    scope.launch { onFailed() }
                }
            }
        },
        onProgress = { req: FileSaveRequest, done: Long ->
            rows[req.id]?.let { TransferCenter.progress(it, done, req.size) }
        },
    )

    return { file: FileDto ->
        val row = TransferCenter.start(file.name, TransferKind.DOWNLOAD, file.size, TransferLane.FILE)
        rows[file.id] = row
        saver(
            FileSaveRequest(
                id = file.id,
                name = file.name,
                mime = file.mimeType,
                size = file.size,
                open = { repo.download(file.id) },
            ),
        )
    }
}
