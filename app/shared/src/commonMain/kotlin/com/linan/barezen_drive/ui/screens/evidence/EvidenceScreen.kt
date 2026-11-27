package com.linan.barezen_drive.ui.screens.evidence

import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.data.evidence.EvidenceCloudVault
import com.linan.barezen_drive.data.evidence.EvidenceErrorKind
import com.linan.barezen_drive.data.evidence.EvidenceException
import com.linan.barezen_drive.data.evidence.EvidenceLens
import com.linan.barezen_drive.data.evidence.EvidenceMode
import com.linan.barezen_drive.data.evidence.EvidencePlaybackRequest
import com.linan.barezen_drive.data.evidence.EvidenceSessionView
import com.linan.barezen_drive.data.evidence.EvidenceVaultStatus
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.platform.EvidencePlatform
import com.linan.barezen_drive.platform.rememberFileSaver
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun EvidenceScreen(
    platform: EvidencePlatform,
    onExit: () -> Unit,
    player: @Composable (EvidencePlaybackRequest, () -> Unit) -> Unit,
) {
    val controller = platform.controller
    val scope = rememberCoroutineScope()
    val strings = LocalStrings.current
    val recording by controller.recording.collectAsState()
    val storage by controller.storage.collectAsState()
    var status by remember { mutableStateOf<EvidenceVaultStatus?>(null) }
    var sessions by remember { mutableStateOf<List<EvidenceSessionView>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<EvidenceErrorKind?>(null) }
    var playback by remember { mutableStateOf<EvidencePlaybackRequest?>(null) }
    var exportKey by remember { mutableStateOf<String?>(null) }
    var cloudVaults by remember { mutableStateOf<List<EvidenceCloudVault>>(emptyList()) }
    var changePassword by remember { mutableStateOf(false) }
    var deleteVault by remember { mutableStateOf(false) }
    var pendingRecording by remember { mutableStateOf<Pair<EvidenceMode, EvidenceLens>?>(null) }
    val saveFile = rememberFileSaver(
        onDone = { request, _ -> controller.closePlayback(request.id) },
        onProgress = { _, _ -> },
    )

    suspend fun refresh() {
        status = controller.status()
        if (status == EvidenceVaultStatus.UNLOCKED) sessions = controller.sessions()
        else sessions = emptyList()
    }

    suspend fun lockAndRefresh() {
        controller.lock()
        playback = null
        refresh()
    }

    DisposableEffect(controller) {
        onDispose { controller.lock() }
    }
    val playbackToken = playback?.token
    DisposableEffect(playbackToken) {
        onDispose { playbackToken?.let(controller::closePlayback) }
    }

    suspend fun beginRecording(mode: EvidenceMode, lens: EvidenceLens) {
        busy = true
        error = null
        runCatching { controller.start(mode, lens); refresh() }
            .onFailure { error = evidenceError(it) }
        busy = false
    }

    platform.AutoLock {
        scope.launch { runCatching { lockAndRefresh() }.onFailure { error = evidenceError(it) } }
    }
    val requestPermissions = platform.rememberRecordingPermissionRequest { granted ->
        if (granted) pendingRecording?.let { (mode, lens) ->
            scope.launch { beginRecording(mode, lens) }
        }
        pendingRecording = null
    }

    LaunchedEffect(controller) {
        runCatching { refresh() }.onFailure { error = (it as? EvidenceException)?.kind ?: EvidenceErrorKind.UNKNOWN }
    }
    LaunchedEffect(recording.sessionId, recording.sealedSegments, recording.uploadLabel, recording.stopping) {
        if (status == EvidenceVaultStatus.UNLOCKED) {
            runCatching { sessions = controller.sessions() }.onFailure { refresh() }
        }
    }
    LaunchedEffect(recording.error) {
        recording.error?.let { error = it }
    }

    BackHandler(enabled = true) {
        if (recording.active) scope.launch { lockAndRefresh() } else onExit()
    }

    if (recording.active) {
        EvidenceRecordingScreen(
            state = recording,
            onStop = { scope.launch { runCatching { controller.stop(); refresh() }.onFailure { error = evidenceError(it) } } },
            onLock = { scope.launch { lockAndRefresh() } },
        )
        return
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(strings.tabEvidence) },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.actionBack)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            error?.let { Text(evidenceErrorText(it), color = MaterialTheme.colorScheme.error) }
            if (busy) CircularProgressIndicator()
            when (status) {
                null -> CircularProgressIndicator()
                EvidenceVaultStatus.UNLOCKED -> EvidenceHome(
                    sessions = sessions,
                    canRecord = controller.canRecord,
                    onRecord = { mode, lens ->
                        pendingRecording = mode to lens
                        requestPermissions(mode)
                    },
                    onPlay = { scope.launch { runCatching { playback = controller.playback(it) }.onFailure { error = evidenceError(it) } } },
                    onExport = { exportKey = it },
                    onDeleteLocal = { scope.launch { runCatching { controller.deleteLocalSegments(it); refresh() }.onFailure { error = evidenceError(it) } } },
                    onChangePassword = { changePassword = true },
                    onDeleteVault = { deleteVault = true },
                    onLock = { scope.launch { lockAndRefresh() } },
                )
                else -> EvidencePasswordPanel(
                    status = status,
                    onSubmit = { password, confirm, restoreId ->
                        scope.launch {
                            busy = true
                            runCatching {
                                when {
                                    restoreId != null -> controller.restore(restoreId, password)
                                    status == EvidenceVaultStatus.MISSING -> {
                                        if (password != confirm) throw EvidenceException(EvidenceErrorKind.PASSWORD_MISMATCH)
                                        controller.createVault(password)
                                    }
                                    else -> controller.unlock(password)
                                }
                                refresh()
                            }.onFailure { error = evidenceError(it) }
                            busy = false
                        }
                    },
                    onRestore = { scope.launch { runCatching { cloudVaults = controller.cloudVaults() }.onFailure { error = evidenceError(it) } } },
                    cloudVaults = cloudVaults,
                )
            }
        }
    }

    playback?.let { request ->
        EvidencePlaybackScreen(
            request = request,
            player = player,
            onClose = { controller.closePlayback(request.token); playback = null },
        )
    }
    exportKey?.let { key ->
        AlertDialog(
            onDismissRequest = { exportKey = null },
            title = { Text(strings.evidenceExportPlaintext) },
            text = { Text(strings.evidenceExportWarning) },
            confirmButton = { TextButton(onClick = {
                exportKey = null
                scope.launch { runCatching { controller.exportRequest(key)?.let(saveFile) }.onFailure { error = evidenceError(it) } }
            }) { Text(strings.actionConfirm) } },
            dismissButton = { TextButton(onClick = { exportKey = null }) { Text(strings.actionCancel) } },
        )
    }
    if (changePassword) PasswordDialog(
        title = strings.evidenceChangePassword,
        confirm = true,
        onDismiss = { changePassword = false },
        onSubmit = { old, new -> scope.launch {
            busy = true
            runCatching { controller.changePassword(old, new); changePassword = false }
                .onFailure { error = evidenceError(it) }
            busy = false
        } },
    )
    if (deleteVault) AlertDialog(
        onDismissRequest = { deleteVault = false },
        title = { Text(strings.evidenceDeleteVault) },
        text = { Text(strings.evidenceDeleteVaultWarning) },
        confirmButton = { TextButton(onClick = {
            deleteVault = false
            scope.launch { runCatching { controller.deleteVault(); refresh() }.onFailure { error = evidenceError(it) } }
        }) { Text(strings.actionDelete) } },
        dismissButton = { TextButton(onClick = { deleteVault = false }) { Text(strings.actionCancel) } },
    )
}

@Composable
private fun EvidencePasswordPanel(
    status: EvidenceVaultStatus?,
    onSubmit: (String, String, String?) -> Unit,
    onRestore: () -> Unit,
    cloudVaults: List<EvidenceCloudVault>,
) {
    val strings = LocalStrings.current
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var restoring by remember { mutableStateOf(false) }
    var restoreId by remember { mutableStateOf<String?>(null) }
    val creating = status == EvidenceVaultStatus.MISSING
    val restoringCloud = restoreId != null
    Text(if (creating) strings.evidenceSetPassword else strings.evidenceUnlock, style = MaterialTheme.typography.titleLarge)
    PasswordField(password, strings.fieldPassword) { password = it }
    if (creating && !restoringCloud) PasswordField(confirmation, strings.actionConfirm) { confirmation = it }
    Text(strings.evidencePasswordHint, style = MaterialTheme.typography.bodySmall)
    Text(strings.evidencePasswordLostWarning, style = MaterialTheme.typography.bodySmall)
    Button(
        enabled = canSubmitEvidencePassword(password, creating, restoringCloud, confirmation),
        onClick = {
            val submitted = password
            val confirmationValue = confirmation
            password = ""
            confirmation = ""
            onSubmit(submitted, confirmationValue, restoreId)
        },
    ) { Text(if (restoringCloud) strings.evidenceRestore else if (creating) strings.evidenceSetPassword else strings.evidenceUnlock) }
    TextButton(onClick = { restoring = !restoring; onRestore() }) { Text(strings.evidenceRecoverCloud) }
    if (restoring) cloudVaults.forEach { vault ->
        TextButton(onClick = { restoreId = vault.vaultId }) { Text("${strings.evidenceRestore} · ${vault.vaultId}") }
    }
}

@Composable
private fun EvidenceHome(
    sessions: List<EvidenceSessionView>,
    canRecord: Boolean,
    onRecord: (EvidenceMode, EvidenceLens) -> Unit,
    onPlay: (String) -> Unit,
    onExport: (String) -> Unit,
    onDeleteLocal: (List<String>) -> Unit,
    onChangePassword: () -> Unit,
    onDeleteVault: () -> Unit,
    onLock: () -> Unit,
) {
    val strings = LocalStrings.current
    var front by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(EvidenceSelection()) }
    val allKeys = sessions.flatMap { session -> session.segments.map { it.key.toString() } }
    val selected = selection.selectedKeys

    if (selection.active) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${strings.selected} · ${selected.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { selection = selection.selectAll(allKeys) }) { Text(strings.selectAll) }
            TextButton(onClick = { selection = selection.clear() }) { Text(strings.actionCancel) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                enabled = selected.size == 1,
                onClick = {
                    selected.singleOrNull()?.let(onPlay)
                    selection = selection.clear()
                },
            ) { Text(strings.evidencePlay) }
            TextButton(
                enabled = selected.size == 1,
                onClick = {
                    selected.singleOrNull()?.let(onExport)
                    selection = selection.clear()
                },
            ) { Text(strings.evidenceExportPlaintext) }
            if (canRecord) {
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        onDeleteLocal(selected.toList())
                        selection = selection.clear()
                    },
                ) { Text(strings.evidenceDeleteLocal) }
            }
        }
    } else {
        if (canRecord) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRecord(EvidenceMode.AUDIO, EvidenceLens.BACK) }) { Text(strings.evidenceAudio) }
                Button(onClick = { onRecord(EvidenceMode.VIDEO, if (front) EvidenceLens.FRONT else EvidenceLens.BACK) }) { Text(strings.evidenceVideo) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!front, { front = false }, { Text(strings.evidenceBackCamera) })
                FilterChip(front, { front = true }, { Text(strings.evidenceFrontCamera) })
            }
        } else Text(strings.evidenceReadOnly)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (canRecord) {
                TextButton(onClick = onChangePassword) { Text(strings.evidenceChangePassword) }
                TextButton(onClick = onDeleteVault) { Text(strings.evidenceDeleteVault) }
            }
            if (allKeys.isNotEmpty()) {
                TextButton(onClick = { selection = EvidenceSelection(active = true) }) { Text(strings.actionSelect) }
            }
            TextButton(onClick = onLock) { Text(strings.evidenceLock) }
        }
    }
    if (sessions.isEmpty()) Text(strings.evidenceNoSessions)
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(sessions, key = { it.sessionId }) { session ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${if (session.mode == EvidenceMode.AUDIO) strings.evidenceAudio else strings.evidenceVideo} · ${session.segmentCount}")
                session.segments.forEach { segment ->
                    val key = segment.key.toString()
                    val isSelected = selection.active && key in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = {
                                    if (selection.active) selection = selection.toggle(key) else onPlay(key)
                                },
                            )
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selection.active) {
                            Checkbox(checked = isSelected, onCheckedChange = { selection = selection.toggle(key) })
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${segment.key.segmentNumber} · ${segment.state}")
                            Text("${segment.cipherBytes}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!selection.active) {
                            TextButton(onClick = { onPlay(key) }) { Text(strings.evidencePlay) }
                            TextButton(onClick = { onExport(key) }) { Text(strings.evidenceExportPlaintext) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordDialog(title: String, confirm: Boolean, onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var old by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(old, LocalStrings.current.fieldPassword) { old = it }
                if (confirm) PasswordField(next, LocalStrings.current.fieldPassword) { next = it }
            }
        },
        confirmButton = { TextButton(enabled = old.isNotEmpty() && (!confirm || next.isNotEmpty()), onClick = { onSubmit(old, next); onDismiss() }) { Text(LocalStrings.current.actionConfirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(LocalStrings.current.actionCancel) } },
    )
}

@Composable
fun EvidenceRecordingScreen(
    state: com.linan.barezen_drive.data.evidence.EvidenceRecordingState,
    onStop: () -> Unit,
    onLock: () -> Unit,
) {
    val strings = LocalStrings.current
    val isVideo = state.mode == EvidenceMode.VIDEO
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (isVideo) strings.evidenceVideo else strings.evidenceAudio) },
                actions = {
                    TextButton(onClick = onLock) { Text(strings.evidenceLock) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onStop,
                        enabled = !state.stopping,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.stopping) strings.evidenceStopping else strings.evidenceStop)
                    }
                    OutlinedButton(onClick = onLock) { Text(strings.evidenceLock) }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                if (isVideo) Icons.Default.Videocam else Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(strings.evidenceRecording, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            Text(
                formatElapsed(state.elapsedMs),
                style = MaterialTheme.typography.displayLarge,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(24.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${strings.evidenceSegments} · ${state.sealedSegments}")
                    state.uploadLabel?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.stopping) {
                        Text(strings.evidenceStopping, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                strings.evidenceScreenOffContinue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

internal fun formatElapsed(ms: Long): String = listOf(ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60)
    .joinToString(":") { it.toString().padStart(2, '0') }

@Composable
fun EvidencePlaybackScreen(
    request: EvidencePlaybackRequest,
    player: @Composable (EvidencePlaybackRequest, () -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(strings.evidencePlay) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.actionClose)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            player(request, onClose)
        }
    }
}

internal fun evidenceError(error: Throwable): EvidenceErrorKind = (error as? EvidenceException)?.kind ?: EvidenceErrorKind.UNKNOWN

@Composable
internal fun evidenceErrorText(kind: EvidenceErrorKind): String = with(LocalStrings.current) {
    when (kind) {
        EvidenceErrorKind.PASSWORD_LENGTH -> evidencePasswordTooShort
        EvidenceErrorKind.PASSWORD_WRONG -> operationFailed
        EvidenceErrorKind.PASSWORD_MISMATCH -> evidencePasswordMismatch
        EvidenceErrorKind.COOLDOWN -> evidenceCooldown
        EvidenceErrorKind.VAULT_NOT_FOUND -> evidenceVaultNotFound
        EvidenceErrorKind.MANIFEST_INVALID -> evidenceCloudManifestInvalid
        EvidenceErrorKind.PERMISSION_REQUIRED, EvidenceErrorKind.NOTIFICATION_PERMISSION_REQUIRED -> evidencePermissionRequired
        EvidenceErrorKind.SERVICE_START_DENIED -> evidenceServiceStartDenied
        EvidenceErrorKind.CAMERA_UNAVAILABLE, EvidenceErrorKind.MICROPHONE_UNAVAILABLE -> evidencePermissionRequired
        EvidenceErrorKind.LOW_STORAGE -> evidenceLowStorage
        EvidenceErrorKind.UPLOAD_FAILED -> evidenceUploadFailed
        EvidenceErrorKind.NETWORK_UNAVAILABLE -> networkCannotConnect
        EvidenceErrorKind.DECRYPT_FAILED -> evidenceDecryptFailed
        EvidenceErrorKind.UNKNOWN -> operationFailed
    }
}
