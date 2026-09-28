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
 * @param onDone receives the request and the saved location (null = cancelled or
 *   failed), so the caller can close that request's row and not another's.
 * @param onProgress receives the request and the bytes written so far, once per
 *   chunk. Platforms without a copy loop never call it.
 */
@Composable
expect fun rememberFileSaver(
    onDone: (FileSaveRequest, String?) -> Unit,
    onProgress: (FileSaveRequest, Long) -> Unit,
): (FileSaveRequest) -> Unit
