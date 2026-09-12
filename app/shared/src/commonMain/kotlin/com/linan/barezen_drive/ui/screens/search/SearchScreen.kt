package com.linan.barezen_drive.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.ui.AvatarButton
import com.linan.barezen_drive.ui.media.formatDateTime
import com.linan.barezen_drive.ui.screens.files.formatFileSize
import kotlinx.coroutines.delay

/**
 * Search tab, Google-Photos style: the last slot in the bottom bar is search,
 * not settings - settings lives behind the top-bar avatar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    files: FilesRepository,
    currentUserId: String?,
    onPreview: (List<FileDto>, Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FileDto>?>(null) }

    // Debounced live search: every keystroke waits 300ms, then queries.
    LaunchedEffect(query) {
        delay(300)
        results = if (query.isBlank()) {
            null
        } else {
            files.search(query.trim()).getOrNull()?.files ?: emptyList()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(LocalStrings.current.tabSearch) },
                actions = {
                    AvatarButton(files, currentUserId, onOpenSettings = onOpenSettings)
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(LocalStrings.current.searchHint) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val shown = results
            when {
                shown == null -> {}
                shown.isEmpty() && query.isNotBlank() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        LocalStrings.current.searchEmpty,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { file ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val index = shown.indexOf(file).coerceAtLeast(0)
                                    onPreview(listOf(file), index)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                fileKindIcon(file.mimeType),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                Text(
                                    formatFileSize(file.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                formatDateTime(file.updatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fileKindIcon(mime: String?): ImageVector = when {
    mime == null -> Icons.Default.Description
    mime.startsWith("image/") -> Icons.Default.Image
    mime.startsWith("video/") -> Icons.Default.VideoFile
    mime.startsWith("audio/") -> Icons.Default.AudioFile
    else -> Icons.Default.Description
}
