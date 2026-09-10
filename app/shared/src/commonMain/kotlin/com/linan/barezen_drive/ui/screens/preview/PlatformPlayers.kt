package com.linan.barezen_drive.ui.screens.preview

import androidx.compose.runtime.Composable
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository

/** Video/audio playback. Android streams through Media3; web opens a browser tab. */
@Composable
expect fun PlatformMediaPlayer(file: FileDto, repo: FilesRepository, isAudio: Boolean)

/** PDF rendering. Android uses pdfium; web opens the browser's PDF viewer. */
@Composable
expect fun PlatformPdfViewer(file: FileDto, repo: FilesRepository)
