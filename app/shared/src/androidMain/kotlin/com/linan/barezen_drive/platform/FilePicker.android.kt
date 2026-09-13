package com.linan.barezen_drive.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidPickedFile(internal val ctx: Context, internal val uri: Uri) : PickedFile {
    override val name: String
    override val size: Long
    override val mimeType: String? = ctx.contentResolver.getType(uri)
    override val originAlbum: String?
    override val originDateMs: Long?

    init {
        var n = uri.lastPathSegment ?: uri.toString()
        var s = 0L
        var album: String? = null
        var dateMs: Long? = null
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndex(OpenableColumns.SIZE)
            // API 29+: the file's own directory (DCIM/Camera) - the same name
            // the phone gallery shows as a category. Older devices fall back
            // to the bucket display name.
            val iPath = c.getColumnIndex(android.provider.MediaStore.MediaColumns.RELATIVE_PATH)
            val iBucket = c.getColumnIndex(android.provider.MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iTaken = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_TAKEN)
            val iMod = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
            if (c.moveToFirst()) {
                if (iName >= 0) c.getString(iName)?.let { n = it }
                if (iSize >= 0) s = c.getLong(iSize)
                if (iTaken >= 0) {
                    // DATE_TAKEN is already MILLISECONDS since the epoch; the
                    // old *1000 here turned every capture time into the year
                    // ~57000 and poisoned the album grouping. DATE_MODIFIED
                    // (the fallback below) IS in seconds.
                    val ms = c.getLong(iTaken)
                    if (ms > 0) dateMs = ms
                }
                if (dateMs == null && iMod >= 0) {
                    val sec = c.getLong(iMod)
                    if (sec > 0) dateMs = sec * 1000
                }
                if (iPath >= 0) {
                    c.getString(iPath)?.let { p ->
                        album = p.trim('/').split('/').lastOrNull()?.takeIf { it.isNotBlank() }
                    }
                }
                if (album == null && iBucket >= 0) album = c.getString(iBucket)
            }
        }
        name = n
        size = s
        originAlbum = album
        originDateMs = dateMs
    }

    // Sequential-read cache. Uploads and the whole-file hash walk a file from
    // offset 0 to EOF, so keeping one open stream and a cursor turns the old
    // O(n^2) "reopen + skip to offset" into a single linear pass. An out-of-order
    // request (cover regeneration restarts at 0, or a resumed chunk jumps ahead)
    // falls back to the reopen+skip path so behaviour stays correct.
    @Volatile
    private var stream: java.io.InputStream? = null

    @Volatile
    private var streamPos: Long = 0L

    private fun closeStream() {
        runCatching { stream?.close() }
        stream = null
        streamPos = 0L
    }

    // read/ins are reassigned in the copy loop; CanBeVal misfires here.
    @Suppress("CanBeVal")
    override suspend fun readRange(offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        val buf = ByteArray(length)
        var ins = stream
        if (ins != null && streamPos == offset) {
            // Sequential continuation from the cached cursor.
            var read = 0
            while (read < length) {
                val r = ins.read(buf, read, length - read)
                if (r < 0) break
                read += r
            }
            if (read == 0) {
                closeStream()
                null
            } else {
                streamPos += read
                buf.copyOf(read)
            }
        } else {
            // Non-sequential: start a fresh stream and skip to the offset.
            closeStream()
            val opened = ctx.contentResolver.openInputStream(uri) ?: return@withContext null
            var toSkip = offset
            try {
                while (toSkip > 0) {
                    val n = opened.skip(toSkip)
                    if (n <= 0) {
                        runCatching { opened.close() }
                        return@withContext null
                    }
                    toSkip -= n
                }
                var read = 0
                while (read < length) {
                    val r = opened.read(buf, read, length - read)
                    if (r < 0) break
                    read += r
                }
                // Keep the stream open at the new cursor only when we consumed a
                // whole block (the caller is walking forward); otherwise the read
                // is likely a one-off random access and we can release immediately.
                if (read == length) {
                    stream = opened
                    streamPos = offset + read
                } else {
                    runCatching { opened.close() }
                }
                if (read == 0) null else buf.copyOf(read)
            } catch (e: Exception) {
                runCatching { opened.close() }
                throw e
            }
        }
    }
}

@Composable
actual fun rememberFilePicker(onResult: (List<PickedFile>) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onResult(uris.map { AndroidPickedFile(ctx, it) })
    }
    return { launcher.launch(arrayOf("*/*")) }
}
