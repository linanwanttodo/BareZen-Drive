package com.linan.barezen_drive.sync

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.linan.barezen_drive.AndroidContext

/**
 * MediaStore reads shared by the sync worker, the change observer and the daily
 * reconciler. Keeping them here means the scan semantics (which albums count,
 * how a photo is classified) live in exactly one place.
 */
object SyncScan {

    /** Every image+video currently on the device, as queue-shaped rows.
     *
     *  [sinceSeconds] switches this to an incremental read: only rows whose
     *  DATE_MODIFIED is at or after that MediaStore epoch are returned. The
     *  caller derives the value from the previous scan's watermark via
     *  [SyncPolicy.scanSinceSeconds], which also applies an overlap window - a
     *  repeated row is harmless because upsertScanned is an idempotent upsert.
     *
     *  A null [sinceSeconds] is a full scan. Only the daily reconciler needs one
     *  (it prunes rows for media that disappeared, which an incremental read
     *  cannot see); the change-triggered pass uses the incremental path so a
     *  photo landing does not walk the whole library. */
    fun scanAll(
        ctx: Context = AndroidContext.app,
        sinceSeconds: Long? = null,
    ): List<ScannedMedia> {
        val out = ArrayList<ScannedMedia>()
        out += queryCollection(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isPhoto = true, sinceSeconds)
        out += queryCollection(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isPhoto = false, sinceSeconds)
        return out
    }

    fun presentUris(items: List<ScannedMedia>): Set<String> = items.mapTo(HashSet(items.size)) { it.uri }

    private fun queryCollection(
        ctx: Context,
        collection: android.net.Uri,
        isPhoto: Boolean,
        sinceSeconds: Long?,
    ): List<ScannedMedia> {
        val result = ArrayList<ScannedMedia>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val selection = if (sinceSeconds != null) "${MediaStore.MediaColumns.DATE_MODIFIED} >= ?" else null
        val selectionArgs = if (sinceSeconds != null) arrayOf(sinceSeconds.toString()) else null
        ctx.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val iPath = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val iBucket = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iMod = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val iTaken = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val uri = ContentUris.withAppendedId(collection, id).toString()
                val album = (if (iPath >= 0) c.getString(iPath)?.trim('/')?.split('/')?.lastOrNull() else null)
                    ?: (if (iBucket >= 0) c.getString(iBucket) else null)
                // Null means "upload straight into the device folder", not "skip"
                // - excluding a whole album is the user's call, via the picker.
                val cleanAlbum = SyncPolicy.albumCategory(album)
                val size = if (iSize >= 0 && !c.isNull(iSize)) c.getLong(iSize) else 0L
                val dateMod = if (iMod >= 0 && !c.isNull(iMod)) c.getLong(iMod) else 0L
                val taken = if (iTaken >= 0 && !c.isNull(iTaken)) c.getLong(iTaken) else null
                result.add(
                    ScannedMedia(
                        uri = uri,
                        bucket = cleanAlbum,
                        name = if (iName >= 0) c.getString(iName) ?: "media" else "media",
                        size = size,
                        dateModified = dateMod,
                        dateTaken = taken,
                        isPhoto = isPhoto,
                    ),
                )
            }
        }
        return result
    }
}
