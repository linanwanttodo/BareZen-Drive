package com.linan.barezen_drive.ui.screens.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.linan.barezen_drive.i18n.LocalStrings

private const val TEXT_PREVIEW_LIMIT = 256 * 1024

/**
 * Text/code preview. Only the first 256KB is fetched (via a Range request,
 * which the server already supports); the rest is available through download.
 */
@Composable
fun TextViewer(file: FileDto, repo: FilesRepository) {
    var text by remember(file.id) { mutableStateOf<String?>(null) }
    var error by remember(file.id) { mutableStateOf(false) }

    LaunchedEffect(file.id) {
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
        val body = content ?: if (error) LocalStrings.current.loadFailed else null
        if (body != null) {
            val vertical = rememberScrollState()
            val horizontal = rememberScrollState()
            SelectionText(body, vertical, horizontal)
        } else if (content == null && !error) {
            Text(
                LocalStrings.current.loading,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun SelectionText(body: String, vertical: androidx.compose.foundation.ScrollState, horizontal: androidx.compose.foundation.ScrollState) {
    androidx.compose.foundation.text.selection.SelectionContainer {
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
