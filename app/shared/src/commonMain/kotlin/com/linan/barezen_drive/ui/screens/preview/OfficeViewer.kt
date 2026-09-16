package com.linan.barezen_drive.ui.screens.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.i18n.LocalStrings
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Office previews above this are refused: extraction reads the whole blob. */
private const val OFFICE_PREVIEW_LIMIT = 20L * 1024 * 1024

/**
 * In-app Office document preview (docx / xlsx / pptx - the "WPS document"
 * formats). An OOXML file is a zip of XML parts, so the common layer downloads
 * it once and [extractOfficeText] pulls the readable content out of it; the
 * result shows as scrollable text like the code viewer. Full layout fidelity
 * (fonts, tables, images) would need a whole office suite - the goal here is
 * "see what is inside without leaving the app", not WPS itself.
 */
@Composable
fun OfficeViewer(file: FileDto, repo: FilesRepository) {
    var text by remember(file.id) { mutableStateOf<String?>(null) }
    var failed by remember(file.id) { mutableStateOf(false) }
    var tooLarge by remember(file.id) { mutableStateOf(false) }

    LaunchedEffect(file.id) {
        if (file.size > OFFICE_PREVIEW_LIMIT) {
            tooLarge = true
            return@LaunchedEffect
        }
        runCatching {
            val bytes = withContext(kotlinx.coroutines.Dispatchers.Default) {
                repo.download(file.id).toByteArray()
            }
            withContext(Dispatchers.Default) {
                extractOfficeText(file.name, bytes)
            }
        }.fold(
            onSuccess = { extracted ->
                if (extracted.isNullOrBlank()) failed = true else text = extracted
            },
            onFailure = { failed = true },
        )
    }

    Box(Modifier.fillMaxSize()) {
        when {
            failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    LocalStrings.current.loadFailed,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
            tooLarge -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    LocalStrings.current.officeTooLargePreview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
            text != null -> Column(Modifier.fillMaxSize()) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    text!!,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                )
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        LocalStrings.current.downloadingPdf,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
