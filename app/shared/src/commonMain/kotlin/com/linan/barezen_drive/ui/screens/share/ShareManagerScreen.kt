package com.linan.barezen_drive.ui.screens.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.ShareDto
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.ui.media.formatDateTime
import kotlinx.coroutines.launch
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.i18n.LocalStrings

/**
 * Central share management: every active link across all files and folders
 * with its access counters, one tap to revoke. The URL itself is only shown
 * once at creation (the token is stored hashed), hence no copy action here.
 */
@Composable
fun ShareManagerScreen(
    repo: FilesRepository,
    onBack: () -> Unit,
) {
    var shares by remember { mutableStateOf<List<ShareDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            repo.listShares().fold(
                onSuccess = { shares = it.shares; error = null },
                onFailure = { error = it.message?.takeIf { m -> m.isNotBlank() } ?: I18n.strings.loadFailed },
            )
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LocalStrings.current.shareManagement) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LocalStrings.current.actionBack)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            Text(
                LocalStrings.current.shareLinkShownOnce,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            val s = shares
            when {
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: LocalStrings.current.loadFailed, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { refresh() }) { Text(LocalStrings.current.actionRetry) }
                    }
                }
                s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                s.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.LinkOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(LocalStrings.current.noShareLinks, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(s, key = { it.id }) { share ->
                        ShareRow(repo, share, onRevoked = { refresh() })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareRow(repo: FilesRepository, share: ShareDto, onRevoked: () -> Unit) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (share.targetType == "folder") Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(share.targetName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(share.expiresAt?.let { LocalStrings.current.expiresAt(formatDateTime(it)) } ?: LocalStrings.current.neverExpires)
                    append(LocalStrings.current.shareStatsSuffix(share.viewCount.toString(), share.downloadCount.toString()))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = {
            scope.launch {
                repo.revokeShare(share.id).fold(
                    onSuccess = { onRevoked() },
                    onFailure = { },
                )
            }
        }) { Text(LocalStrings.current.actionClose, color = MaterialTheme.colorScheme.error) }
    }
}
