package com.linan.barezen_drive.ui.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.ui.graphics.vector.ImageVector

/** Icon used when a file has no thumbnail (unsupported type or cover missing). */
fun fileIcon(mime: String?): ImageVector = when {
    mime == "application/pdf" -> Icons.Default.PictureAsPdf
    mime != null && mime.startsWith("image/") -> Icons.Default.Image
    mime != null && mime.startsWith("video/") -> Icons.Default.Movie
    mime != null && mime.startsWith("audio/") -> Icons.Default.MusicNote
    mime != null && mime.startsWith("text/") -> Icons.Default.Description
    else -> Icons.Default.InsertDriveFile
}
