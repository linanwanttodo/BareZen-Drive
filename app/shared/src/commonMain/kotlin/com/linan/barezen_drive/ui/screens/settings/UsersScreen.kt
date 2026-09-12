package com.linan.barezen_drive.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.AdminUserDto
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.ui.media.formatDateTime
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator

/**
 * Owner-facing account list: who registered, how much they store, and a
 * destructive delete that removes the account with all of its data. The
 * signed-in owner is marked so they do not delete themselves by accident.
 */
@Composable
fun UsersScreen(
    currentUserId: String?,
    users: suspend () -> List<AdminUserDto>,
    onDelete: suspend (AdminUserDto) -> Unit,
    onBack: () -> Unit,
) {
    var list by remember { mutableStateOf<List<AdminUserDto>?>(null) }
    var deleting by remember { mutableStateOf<AdminUserDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            list = null
            list = runCatching { users() }.getOrElse { error = I18n.strings.loadFailed; emptyList() }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LocalStrings.current.userManagement) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LocalStrings.current.actionBack)
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Text(
                LocalStrings.current.userManagementHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                error != null -> Column(Modifier.padding(16.dp)) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { error = null; reload() }) { Text(LocalStrings.current.actionRetry) }
                }
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> LazyColumn {
                    items(list!!, key = { it.id }) { user ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.padding(horizontal = 6.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(user.username, style = MaterialTheme.typography.bodyLarge)
                                    if (user.id == currentUserId) {
                                        Spacer(Modifier.padding(horizontal = 4.dp))
                                        Text(
                                            LocalStrings.current.you,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Text(
                                    formatDateTime(user.createdAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                LocalStrings.current.fileCount(user.fileCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(onClick = { deleting = user }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = LocalStrings.current.actionDelete,
                                    tint = if (user.id == currentUserId) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    deleting?.let { user ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(LocalStrings.current.deleteUser) },
            text = {
                Text(
                    if (user.id == currentUserId) LocalStrings.current.deleteUserSelfWarning
                    else LocalStrings.current.deleteUserWarning(user.username),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        onDelete(user)
                        reload()
                    }
                }) { Text(LocalStrings.current.actionDelete, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(LocalStrings.current.actionCancel) }
            },
        )
    }
}
