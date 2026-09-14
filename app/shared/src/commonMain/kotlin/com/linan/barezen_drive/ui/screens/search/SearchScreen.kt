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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.ui.media.formatDateTime
import com.linan.barezen_drive.ui.shell.BottomBarClearance
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha
import androidx.compose.foundation.layout.PaddingValues
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
    onPreview: (List<FileDto>, Int) -> Unit,
    /**
     * Shared from the shell so all four tab top bars render one and the same
     * avatar. Building it in here only had `currentUserId` to work with, so no
     * username reached it: the circle fell back to the blank-account colour
     * (0xFF607D8B) and the "." letter instead of the account initial the other
     * three tabs show.
     */
    avatar: @Composable () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FileDto>?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }

    // Debounced live search: every keystroke waits 300ms, then queries.
    LaunchedEffect(query) {
        delay(300)
        if (query.isBlank()) {
            results = null
            searchError = null
            return@LaunchedEffect
        }
        // A failed query must read as a failure, not as "no results".
        files.search(query.trim()).fold(
            onSuccess = {
                results = it.files
                searchError = null
            },
            onFailure = { e ->
                results = null
                searchError = e.message?.takeIf { it.isNotBlank() } ?: I18n.strings.loadFailed
            },
        )
    }

    Scaffold(
        // Translucent panel like the other tabs: an opaque surface here
        // painted over the wallpaper layer MainShell draws behind content.
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
        topBar = {
            TopAppBar(
                // Transparent so the wallpaper layer MainShell paints behind
                // this tab shows through; the default container color is
                // opaque and renders as a hard band across the top.
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(LocalStrings.current.tabSearch) },
                actions = { avatar() },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(LocalStrings.current.searchHint) },
                // Capsule instead of the default outlined rectangle (4 dp): a
                // search field reads as a rounded pill in the mainstream
                // galleries and file managers; extraLarge is 28 dp, exactly
                // half of this field's 56 dp height.
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val shown = results
            val err = searchError
            when {
                err != null && query.isNotBlank() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(err, color = MaterialTheme.colorScheme.error)
                }
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
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BottomBarClearance)) {
                    itemsIndexed(shown, key = { _, f -> f.id }) { i, file ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPreview(shown, i) }
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
