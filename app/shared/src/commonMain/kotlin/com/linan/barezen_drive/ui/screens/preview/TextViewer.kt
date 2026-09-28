package com.linan.barezen_drive.ui.screens.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.ui.component.EmptyState
import com.linan.barezen_drive.ui.component.SkeletonList
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.linan.barezen_drive.i18n.LocalStrings
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.selection.SelectionContainer

private const val TEXT_PREVIEW_LIMIT = 256 * 1024

/**
 * Text/code preview. Only the first 256KB is fetched (via a Range request,
 * which the server already supports); the rest is available through download.
 */
@Composable
fun TextViewer(file: FileDto, repo: FilesRepository) {
    var text by remember(file.id) { mutableStateOf<String?>(null) }
    var error by remember(file.id) { mutableStateOf(false) }
    // Bumped by the retry button; the effect keys on it so a retry re-runs the
    // fetch instead of re-rendering the same failure.
    var retrySignal by remember(file.id) { mutableIntStateOf(0) }

    LaunchedEffect(file.id, retrySignal) {
        // An empty file has no valid byte range; requesting one would answer
        // 416. Hand back empty text without touching the network.
        if (file.size == 0L) {
            text = ""
            return@LaunchedEffect
        }
        runCatching {
            withContext(Dispatchers.Default) {
                // The Range header caps the response at TEXT_PREVIEW_LIMIT, so
                // reading it fully is bounded and small.
                repo.download(file.id, 0L until TEXT_PREVIEW_LIMIT).toByteArray().decodeToString()
            }
        }.fold(
            onSuccess = { text = it },
            onFailure = { error = true },
        )
    }

    val content = text
    Column(Modifier.fillMaxSize()) {
        if (content != null && file.size > TEXT_PREVIEW_LIMIT) {
            Text(
                LocalStrings.current.textPreviewTruncated,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        // A failed load used to be rendered *as the document*: the error string
        // went into the same SelectionText as the file body, so the user read
        // "load failed" as the file's contents with no way to tell and a retry
        // link nowhere. Failure gets its own block, and the body stays empty.
        if (content == null && error) {
            EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = LocalStrings.current.loadFailed,
                modifier = Modifier.fillMaxSize(),
                actionLabel = LocalStrings.current.actionRetry,
                onAction = {
                    error = false
                    text = null
                    retrySignal++
                },
            )
            return@Column
        }
        val body = content
        if (body != null) {
            val vertical = rememberScrollState()
            val horizontal = rememberScrollState()
            SelectionText(body, vertical, horizontal)
        } else {
            // Skeleton lines, not the word "loading" in the body position - the
            // body is either the file or empty, never a status message.
            SkeletonList(modifier = Modifier.fillMaxSize(), rows = 8)
        }
    }
}

@Composable
private fun SelectionText(body: String, vertical: ScrollState, horizontal: ScrollState) {
    SelectionContainer {
        Text(
            body,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(vertical)
                .horizontalScroll(horizontal)
                .padding(12.dp),
        )
    }
}
