package com.linan.barezen_drive.ui.screens.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.platform.BackupBucket
import com.linan.barezen_drive.platform.MediaSync

/**
 * Per-album backup picker (feature 6). Lists every device album with its item
 * count and a checkbox; unchecking a bucket excludes it from the sync queue on
 * the next pass. Android-only - other targets never surface the entry.
 */
@Composable
fun SyncAlbumsScreen(
    onBack: () -> Unit,
    onSyncNow: () -> Unit,
) {
    val strings = LocalStrings.current
    var buckets by remember { mutableStateOf<List<BackupBucket>?>(null) }
    // Local mirror of the inclusion flags. The list is read exactly once here:
    // MediaSync.listBuckets() runs a full MediaStore scan, so re-reading it after
    // every checkbox - the previous behaviour - cost one complete gallery pass per
    // tap on the one screen that exists to be tapped through. Writes still go
    // straight through to the queue, which is what actually persists the choice.
    var included by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    // null buckets means "still loading"; this flag separates "the scan failed"
    // (denied media permission) from "the scan worked and found nothing", which
    // need opposite treatments: one is a retry, the other is genuinely empty.
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loadFailed = false
        buckets = runCatching { MediaSync.listBuckets() }
            .onFailure { loadFailed = true }
            .getOrNull()
        buckets?.let { loaded -> included = loaded.associate { it.name to it.included } }
    }

    fun setIncluded(name: String, on: Boolean) {
        included = included + (name to on)
        MediaSync.setBucketIncluded(name, on)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.backupAlbumsTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.actionBack)
                    }
                },
                actions = {
                    // Bulk restore: the mirror is replaced in one assignment (not
                    // per row) so the list recomposes once, and the queue re-drive
                    // collapses into a single enqueue server-side (unique work
                    // with KEEP) no matter how many albums are restored.
                    TextButton(
                        onClick = {
                            val loaded = buckets ?: return@TextButton
                            included = loaded.associate { it.name to true }
                            loaded.forEach { MediaSync.setBucketIncluded(it.name, true) }
                        },
                        enabled = !buckets.isNullOrEmpty(),
                    ) { Text(strings.backupAlbumsReviewAll) }
                    TextButton(onClick = onSyncNow) { Text(strings.backupSyncNow) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Text(
                strings.backupAlbumsHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                loadFailed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(strings.loadFailed, color = MaterialTheme.colorScheme.error)
                }
                buckets == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                buckets!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(strings.backupAlbumsEmpty, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn {
                    items(buckets!!, key = { it.name }) { bucket ->
                        val on = included[bucket.name] ?: bucket.included
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { setIncluded(bucket.name, !on) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = { checked -> setIncluded(bucket.name, checked) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(bucket.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    buildString {
                                        append(bucket.total)
                                        append(' ')
                                        append(strings.backupAlbumItem)
                                        append(" · ")
                                        append(if (on) strings.backupOn else strings.backupOff)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    // Excluded albums are called out in colour: the
                                    // checkbox alone is easy to miss on a long list.
                                    color = if (on) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
