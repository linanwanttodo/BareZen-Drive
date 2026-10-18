package com.linan.barezen_drive.ui.screens.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository
import kotlinx.browser.window
import kotlinx.coroutines.launch
import com.linan.barezen_drive.i18n.LocalStrings

/**
 * Web previews for video/audio/PDF hand off to the browser's native players
 * in a new tab through a short-lived signed URL (a browser tab cannot send
 * Bearer headers).
 *
 * Opening is the click's job and nothing else. Composing a page is not a user
 * gesture: the pager disposes a page as it is swiped past and re-composes it on
 * the way back, so an automatic first open attempted a tab for every media file
 * visited - one tab per video for a user who allowed popups, and a blocked
 * attempt plus an error line for everyone else.
 */
@Composable
actual fun PlatformMediaPlayer(file: FileDto, repo: FilesRepository, isAudio: Boolean) {
    OpenInNewTab(file, repo, if (isAudio) LocalStrings.current.mediaAudio else LocalStrings.current.mediaVideo)
}

@Composable
actual fun PlatformPdfViewer(file: FileDto, repo: FilesRepository) {
    OpenInNewTab(file, repo, "PDF")
}

@Composable
private fun OpenInNewTab(file: FileDto, repo: FilesRepository, label: String) {
    val scope = rememberCoroutineScope()
    var failed by remember(file.id) { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(LocalStrings.current.openInNewTab(label), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                LocalStrings.current.nativePlayerHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (failed) {
                Text(LocalStrings.current.fetchLinkFailed, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = {
                scope.launch {
                    repo.fileLink(file.id).fold(
                        onSuccess = { window.open(repo.baseUrl + it.url, "_blank") },
                        onFailure = { failed = true },
                    )
                }
            }) {
                Text(if (failed) LocalStrings.current.actionRetry else LocalStrings.current.reopen)
            }
        }
    }
}
