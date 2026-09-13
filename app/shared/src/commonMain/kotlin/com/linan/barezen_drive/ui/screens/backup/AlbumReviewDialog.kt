package com.linan.barezen_drive.ui.screens.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.platform.BackupBucket
import com.linan.barezen_drive.platform.MediaSync

/**
 * A device can report dozens of albums, most of them holding a single image;
 * listing all of them is what makes people dismiss a prompt without reading it.
 */
private const val TOP_N = 8

/**
 * First-run album review, shown the moment automatic backup is switched on.
 *
 * Mirrors what Google Photos does ("Review which device folders you want to back
 * up"): instead of silently queueing the entire gallery - WeChat, Telegram and
 * friends drop multi-gigabyte cache albums into MediaStore - the user sees the
 * busiest albums first and unchecks the ones that should not leave the phone.
 * Only [TOP_N] are offered; the complete list stays a tap away under "Backup
 * albums", which is where the choice can be revisited afterwards.
 *
 * Changes are written through as they are made (the same as the full picker), so
 * dismissing by tapping outside keeps whatever was already decided.
 *
 * [onDone] carries whether the review actually happened. A scan that could not
 * read MediaStore (denied permission) leaves nothing to review, and treating
 * that as an answer would burn the one-shot flag: the prompt would never be
 * offered again even though the user never saw a single album. Callers must
 * therefore only mark the review done when [onDone] receives true.
 */
@Composable
fun AlbumReviewDialog(onDone: (reviewed: Boolean) -> Unit) {
    val strings = LocalStrings.current
    var buckets by remember { mutableStateOf<List<BackupBucket>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    // Local mirror of the exclusion set: the list is read once so toggling a box
    // does not re-scan MediaStore behind a dialog.
    var excluded by remember { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(Unit) {
        buckets = runCatching { MediaSync.listBuckets() }
            .onFailure { loadFailed = true }
            .getOrNull()
        buckets?.let { loaded ->
            excluded = loaded.filter { !it.included }.mapTo(mutableSetOf()) { it.name }
        }
    }

    val top = remember(buckets) {
        buckets?.sortedByDescending { it.total }?.take(TOP_N).orEmpty()
    }

    fun setIncluded(name: String, included: Boolean) {
        excluded = if (included) excluded - name else excluded + name
        MediaSync.setBucketIncluded(name, included)
    }

    fun close() = onDone(!loadFailed)

    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(strings.backupAlbumsReviewTitle) },
        text = {
            Column {
                Text(
                    strings.backupAlbumsReviewHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loadFailed -> Text(
                        strings.loadFailed,
                        color = MaterialTheme.colorScheme.error,
                    )
                    buckets == null -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    top.isEmpty() -> Text(
                        strings.backupAlbumsEmpty,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Column(
                        Modifier
                            .fillMaxWidth()
                            // Bounded so the dialog cannot grow past the screen on
                            // a device with many large albums.
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        top.forEach { bucket ->
                            val checked = bucket.name !in excluded
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { setIncluded(bucket.name, !checked) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { on -> setIncluded(bucket.name, on) },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    bucket.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${bucket.total}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        // "Back up all" is the escape hatch for someone who does want everything.
        // It only clears the boxes that are on screen: an exclusion the user made
        // earlier from the full picker is a deliberate choice, not something to
        // silently undo from a shortlist that never showed it.
        dismissButton = {
            TextButton(
                onClick = {
                    top.forEach { MediaSync.setBucketIncluded(it.name, true) }
                    close()
                },
            ) { Text(strings.backupAlbumsReviewAll) }
        },
        confirmButton = {
            TextButton(onClick = ::close) { Text(strings.actionDone) }
        },
    )
}
