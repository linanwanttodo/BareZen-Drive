package com.linan.barezen_drive.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.WebdavTokenDto
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.platform.copyToClipboard
import com.linan.barezen_drive.ui.component.EmptyState
import com.linan.barezen_drive.ui.media.formatDateTime
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * Mirrors WebdavTokenService.MAX_LABEL_LENGTH on the server, so the field
 * cannot be filled past what POST /api/webdav/tokens will accept.
 */
private const val MAX_TOKEN_LABEL_LENGTH = 64

/**
 * App passwords for WebDAV mounts.
 *
 * The plaintext of a freshly minted password is shown exactly once, in a modal
 * that cannot be dismissed by tapping outside or pressing back: the server only
 * stores a hash, so whatever the user does not copy right now is gone for good.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebdavTokensScreen(
    repo: FilesRepository,
    onBack: () -> Unit,
) {
    var tokens by remember { mutableStateOf<List<WebdavTokenDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var minting by remember { mutableStateOf(false) }
    /** Non-null exactly while the one-shot plaintext dialog is up. */
    var fresh by remember { mutableStateOf<FreshToken?>(null) }
    var plaintextCopied by remember { mutableStateOf(false) }
    var confirmingRevoke by remember { mutableStateOf<WebdavTokenDto?>(null) }
    // remembered, not bare: a SnackbarHostState rebuilt on every recomposition
    // is a different instance from the one a launched coroutine holds, so the
    // host draws nothing and showSnackbar suspends forever.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            repo.listWebdavTokens().fold(
                onSuccess = { tokens = it.tokens; error = null },
                onFailure = { error = it.message?.takeIf { m -> m.isNotBlank() } ?: I18n.strings.loadFailed },
            )
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(LocalStrings.current.webdavSettings) },
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
            val s = tokens
            Box(Modifier.weight(1f)) {
                when {
                    error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                error ?: LocalStrings.current.loadFailed,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { refresh() }) { Text(LocalStrings.current.actionRetry) }
                        }
                    }

                    s == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    // Empty state = icon + copy + a primary action. Copy alone
                    // leaves "no mounts yet" looking like a dead end.
                    s.isEmpty() -> EmptyState(
                        icon = Icons.Default.CloudOff,
                        title = LocalStrings.current.webdavNoTokens,
                        subtitle = LocalStrings.current.webdavNoTokensHint,
                        actionLabel = LocalStrings.current.webdavNewMount,
                        onAction = { creating = true },
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(s, key = { it.id }) { token ->
                            WebdavTokenRow(token = token, onRevoke = { confirmingRevoke = token })
                            HorizontalDivider()
                        }
                    }
                }
            }

            // Only when there is something to scroll: with an empty list the
            // empty state already owns the primary action, and two identical
            // buttons on one screen is noise, not emphasis.
            if (s != null && s.isNotEmpty()) {
                HorizontalDivider()
                // A filled button at the bottom rather than an app-bar icon:
                // minting is this page's primary action and must have a target
                // the size of a finger, not a glyph the user has to guess.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = { creating = true },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(LocalStrings.current.webdavNewMount, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    if (creating) {
        CreateTokenDialog(
            busy = minting,
            // Inert while the POST is in flight: letting the dialog close would
            // leave `minting` true and the next open permanently disabled.
            onDismiss = { if (!minting) creating = false },
            onCreate = { label, readOnly ->
                minting = true
                scope.launch {
                    repo.createWebdavToken(label, readOnly).fold(
                        onSuccess = { created ->
                            minting = false
                            creating = false
                            // The plaintext lives in this one state object and
                            // nowhere else, so dismissing the dialog really does
                            // destroy it - which is what the copy below says.
                            fresh = FreshToken(created.plaintext, created.token.label)
                            plaintextCopied = false
                            refresh()
                        },
                        onFailure = {
                            minting = false
                            snackbar.showSnackbar(
                                it.message?.takeIf { m -> m.isNotBlank() } ?: I18n.strings.operationFailed,
                            )
                        },
                    )
                }
            },
        )
    }

    fresh?.let { shown ->
        OneTimePlaintextDialog(
            token = shown,
            copied = plaintextCopied,
            onCopy = {
                scope.launch {
                    // I18n.strings, not LocalStrings: inside a coroutine there
                    // is no composition to read a CompositionLocal from.
                    if (copyToClipboard(shown.plaintext)) {
                        plaintextCopied = true
                        snackbar.showSnackbar(I18n.strings.webdavTokenCopied)
                    } else {
                        // SelectionContainer is the fallback that matters: the
                        // password below stays selectable when the clipboard
                        // API is unreachable, so nothing is truly lost.
                        snackbar.showSnackbar(I18n.strings.webdavTokenCopyFailed)
                    }
                }
            },
            onAcknowledge = { fresh = null },
        )
    }

    confirmingRevoke?.let { target ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { confirmingRevoke = null },
            title = { Text(LocalStrings.current.webdavTokenRevoke) },
            text = { Text(LocalStrings.current.webdavTokenRevokeConfirm(target.label)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRevoke = null
                    scope.launch {
                        repo.revokeWebdavToken(target.id).fold(
                            onSuccess = { refresh() },
                            // A refused revoke must not look like a success:
                            // the mount is still live, so report it.
                            onFailure = {
                                snackbar.showSnackbar(
                                    it.message?.takeIf { m -> m.isNotBlank() } ?: I18n.strings.operationFailed,
                                )
                            },
                        )
                    }
                }) {
                    Text(LocalStrings.current.webdavTokenRevoke, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRevoke = null }) { Text(LocalStrings.current.actionCancel) }
            },
        )
    }
}

/** The plaintext plus the label it belongs to; nothing else is kept. */
private class FreshToken(val plaintext: String, val label: String)

@Composable
private fun WebdavTokenRow(
    token: WebdavTokenDto,
    onRevoke: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Key,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                token.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    // 0 is the server's "never authenticated yet", not a date:
                    // formatting it would print 1970.
                    append(
                        if (token.lastUsedAt <= 0L) LocalStrings.current.webdavNeverUsed
                        else LocalStrings.current.webdavLastUsed(epochLabel(token.lastUsedAt)),
                    )
                    append(" · ")
                    append(LocalStrings.current.webdavCreatedAt(epochLabel(token.createdAt)))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Read-only is a badge, not a switch: flipping it here would not reach
        // an already-mounted device, so a toggle would promise something this
        // screen cannot deliver.
        if (token.readOnly) {
            Text(
                LocalStrings.current.webdavReadOnly,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        TextButton(onClick = onRevoke) {
            Text(LocalStrings.current.webdavTokenRevoke, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CreateTokenDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (label: String, readOnly: Boolean) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var readOnly by remember { mutableStateOf(false) }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = onDismiss,
        title = { Text(LocalStrings.current.webdavNewMount) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    // Mirrors the server's own limit so the field cannot be
                    // filled past what POST /api/webdav/tokens will accept.
                    onValueChange = { if (it.length <= MAX_TOKEN_LABEL_LENGTH) label = it },
                    label = { Text(LocalStrings.current.webdavTokenLabel) },
                    supportingText = { Text(LocalStrings.current.webdavTokenLabelHint) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(selected = readOnly, onClick = { readOnly = !readOnly })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(LocalStrings.current.webdavReadOnly, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            LocalStrings.current.webdavReadOnlyHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = readOnly, onCheckedChange = { readOnly = it })
                }
            }
        },
        confirmButton = {
            // Disabled, not server-rejected: a blank label is a local certainty
            // and a round trip to be told so is pure latency.
            Button(
                enabled = !busy && label.isNotBlank(),
                onClick = { onCreate(label.trim(), readOnly) },
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    if (busy) I18n.strings.creating else LocalStrings.current.webdavCreateMount,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(LocalStrings.current.actionCancel) }
        },
    )
}

/**
 * The one and only view of a mount's password.
 *
 * `onDismissRequest` is empty on purpose: dismissing on an outside tap or a back
 * press would throw away the only copy the user will ever receive, without them
 * having read it. "I've saved it" is the single way out.
 */
@Composable
private fun OneTimePlaintextDialog(
    token: FreshToken,
    copied: Boolean,
    onCopy: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = {},
        title = { Text(LocalStrings.current.webdavTokenCreated(token.label)) },
        text = {
            Column {
                Text(
                    LocalStrings.current.webdavTokenShownOnce,
                    style = MaterialTheme.typography.bodyMedium,
                    // Caution, not error: nothing has gone wrong yet, but the
                    // next back press would lose the only copy.
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.height(12.dp))
                // Monospace so a wrong character is visible, and selectable so
                // the password can still be read out or copied by hand when the
                // clipboard API is unavailable.
                SelectionContainer {
                    Text(
                        token.plaintext,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCopy) {
                    Text(if (copied) LocalStrings.current.copied else LocalStrings.current.webdavTokenCopy)
                }
            }
        },
        confirmButton = {
            Button(onClick = onAcknowledge, shape = RoundedCornerShape(8.dp)) {
                Text(LocalStrings.current.webdavTokenSavedAck, style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

/** Epoch millis into the shared "YYYY-MM-DD HH:mm" shape. */
private fun epochLabel(epochMs: Long): String =
    formatDateTime(Instant.fromEpochMilliseconds(epochMs).toString())
