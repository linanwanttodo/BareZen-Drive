package com.linan.barezen_drive.ui.screens.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.i18n.LocalStrings

/**
 * Full-screen preview. The backstack entry carries the whole listing context
 * so images can be swiped left/right, and the kind dispatch below routes to
 * the platform player/viewer for video, audio and PDF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    files: List<FileDto>,
    initialIndex: Int,
    repo: FilesRepository,
    onBack: () -> Unit,
) {
    val index by remember(initialIndex, files) {
        mutableIntStateOf(initialIndex.coerceIn(0, (files.size - 1).coerceAtLeast(0)))
    }
    val file = files.getOrNull(index) ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LocalStrings.current.actionBack)
                    }
                },
            )
        },
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            when (PreviewKind.of(file)) {
                PreviewKind.IMAGE -> ImageViewer(files, index, repo)
                PreviewKind.VIDEO -> PlatformMediaPlayer(file, repo, isAudio = false)
                PreviewKind.AUDIO -> PlatformMediaPlayer(file, repo, isAudio = true)
                PreviewKind.TEXT -> TextViewer(file, repo)
                PreviewKind.PDF -> PlatformPdfViewer(file, repo)
                PreviewKind.OTHER -> Unsupported(file)
            }
        }
    }
}

@Composable
private fun Unsupported(file: FileDto) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(LocalStrings.current.previewUnsupported, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                LocalStrings.current.previewDownloadHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
