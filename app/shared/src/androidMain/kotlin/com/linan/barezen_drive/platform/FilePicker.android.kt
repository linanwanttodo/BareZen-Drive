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

    init {
        var n = uri.lastPathSegment ?: uri.toString()
        var s = 0L
        var album: String? = null
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndex(OpenableColumns.SIZE)
            // API 29+: the file's own directory (DCIM/Camera) - the same name
            // the phone gallery shows as a category. Older devices fall back
            // to the bucket display name.
            val iPath = c.getColumnIndex(android.provider.MediaStore.MediaColumns.RELATIVE_PATH)
            val iBucket = c.getColumnIndex(android.provider.MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            if (c.moveToFirst()) {
                if (iName >= 0) c.getString(iName)?.let { n = it }
                if (iSize >= 0) s = c.getLong(iSize)
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
    }

    override suspend fun readRange(offset: Long, length: Int): ByteArray? = withContext(Dispatchers.IO) {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            var toSkip = offset
            while (toSkip > 0) {
                val n = ins.skip(toSkip)
                if (n <= 0) return@use null
                toSkip -= n
            }
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val r = ins.read(buf, read, length - read)
                if (r < 0) break
                read += r
            }
            if (read == 0) null else buf.copyOf(read)
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
