package com.linan.barezen_drive.platform

import androidx.compose.runtime.Composable
import io.ktor.utils.io.ByteReadChannel

/**
 * One "save this to the user's storage" request.
 *
 * It carries the size and an id so the caller can open a transfer-centre row
 * before the platform starts copying and close it when the copy ends - a
 * download used to be a black box from the moment the save dialog closed.
 */
data class FileSaveRequest(
    /** Caller-side key, normally the file id: maps a progress callback to a row. */
    val id: String,
    val name: String,
    val mime: String?,
    val size: Long,
    val open: suspend () -> ByteReadChannel,
)

/**
 * How one save ended.
 *
 * The saver used to report a `String?` - the saved location, or null for both
 * "the user closed the system save dialog" and "the write failed". Callers
 * could not tell those apart, so the screens that consume this all reported a
 * dismissed save as a failed download. (The interim workaround inferred the
 * difference from whether a progress callback had fired, which in turn swallowed
 * a write that died before its very first byte.) The platform knows which it is,
 * so it says so.
 */
sealed interface FileSaveResult {
    /** The bytes are in place. [location] is a content uri on Android, the file name handed to the browser on web. */
    data class Saved(val location: String) : FileSaveResult

    /** The user backed out before anything was copied. Not an error - stay silent. */
    data object Cancelled : FileSaveResult

    /** The copy did not finish. [reason] is a short diagnostic hint; may be null. */
    data class Failed(val reason: String?) : FileSaveResult
}

/**
 * What a finished save means for the caller's UI, derived from [FileSaveResult].
 *
 * This is the single decision every save consumer makes: close the transfer row,
 * say nothing, or show the failure. It is a function of the explicit result, not
 * of whether progress happened to be reported.
 */
enum class SaveOutcome {
    /** The bytes are saved: close the row as done. */
    DONE,

    /** The user cancelled: nothing to report, keep quiet. */
    SILENT,

    /** The save failed: tell the user. */
    FAILED,
}

fun FileSaveResult.outcome(): SaveOutcome = when (this) {
    is FileSaveResult.Saved -> SaveOutcome.DONE
    FileSaveResult.Cancelled -> SaveOutcome.SILENT
    is FileSaveResult.Failed -> SaveOutcome.FAILED
}

/**
 * @param onDone receives the request and how it ended, so the caller can close
 *   that request's row (and not another's) and decide whether the outcome is
 *   worth telling the user about. Exactly one of [FileSaveResult.Saved] /
 *   [FileSaveResult.Cancelled] / [FileSaveResult.Failed].
 * @param onProgress receives the request and the bytes written so far, once per
 *   chunk. Platforms without a copy loop never call it.
 */
@Composable
expect fun rememberFileSaver(
    onDone: (FileSaveRequest, FileSaveResult) -> Unit,
    onProgress: (FileSaveRequest, Long) -> Unit,
): (FileSaveRequest) -> Unit
