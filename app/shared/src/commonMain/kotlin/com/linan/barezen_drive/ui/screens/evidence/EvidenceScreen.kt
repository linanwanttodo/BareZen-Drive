package com.linan.barezen_drive.ui.screens.evidence

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun EvidenceScreen(platform: EvidencePlatform, player: @Composable (EvidencePlaybackRequest, () -> Unit) -> Unit) {
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

    if (recording.active) {
        EvidenceRecordingScreen(
            state = recording,
            onStop = { scope.launch { runCatching { controller.stop(); refresh() }.onFailure { error = evidenceError(it) } } },
            onLock = { scope.launch { lockAndRefresh() } },
        )
        return
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(strings.tabEvidence, style = MaterialTheme.typography.headlineSmall)
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
    Text(if (creating) strings.evidenceSetPassword else strings.evidenceUnlock, style = MaterialTheme.typography.titleLarge)
    PasswordField(password, strings.fieldPassword) { password = it }
    if (creating) PasswordField(confirmation, strings.actionConfirm) { confirmation = it }
    Text(strings.evidencePasswordHint, style = MaterialTheme.typography.bodySmall)
    Text(strings.evidencePasswordLostWarning, style = MaterialTheme.typography.bodySmall)
    Button(
        enabled = password.isNotEmpty() && (!creating || password == confirmation),
        onClick = {
            val submitted = password
            val confirmationValue = confirmation
            password = ""
            confirmation = ""
            onSubmit(submitted, confirmationValue, restoreId)
        },
    ) { Text(if (creating) strings.evidenceSetPassword else strings.evidenceUnlock) }
    if (!creating) TextButton(onClick = { restoring = !restoring; onRestore() }) { Text(strings.evidenceRecoverCloud) }
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canRecord) {
            TextButton(onClick = onChangePassword) { Text(strings.evidenceChangePassword) }
            TextButton(onClick = onDeleteVault) { Text(strings.evidenceDeleteVault) }
        }
        TextButton(onClick = onLock) { Text(strings.evidenceLock) }
    }
    if (sessions.isEmpty()) Text(strings.evidenceNoSessions)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(sessions, key = { it.sessionId }) { session ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${if (session.mode == EvidenceMode.AUDIO) strings.evidenceAudio else strings.evidenceVideo} · ${session.segmentCount}")
                session.segments.forEach { segment ->
                    Text("${segment.key.segmentNumber} · ${segment.state} · ${segment.cipherBytes}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onPlay(segment.key.toString()) }) { Text(strings.evidencePlay) }
                        TextButton(onClick = { onExport(segment.key.toString()) }) { Text(strings.evidenceExportPlaintext) }
                    }
                }
                val keys = session.segments.filter { it.state.name == "UPLOADED" }.map { it.key.toString() }
                if (canRecord && keys.isNotEmpty()) TextButton(onClick = { onDeleteLocal(keys) }) { Text(strings.evidenceDeleteLocal) }
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
    var controls by remember { mutableStateOf(true) }
    LaunchedEffect(controls, state.elapsedMs / 1_000) {
        if (controls) { delay(3_000); controls = false }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { controls = true }) {
        Column(Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatElapsed(state.elapsedMs), color = Color.White, style = MaterialTheme.typography.displaySmall)
            Text("${state.sealedSegments} · ${state.uploadLabel.orEmpty()}", color = Color.White)
            if (controls) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onStop) { Text(strings.evidenceStop) }
                    TextButton(onClick = onLock) { Text(strings.evidenceLock) }
                }
            }
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
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        player(request, onClose)
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Text(LocalStrings.current.actionClose, color = Color.White)
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
        EvidenceErrorKind.UPLOAD_FAILED, EvidenceErrorKind.NETWORK_UNAVAILABLE -> evidenceUploadFailed
        EvidenceErrorKind.DECRYPT_FAILED -> evidenceDecryptFailed
        EvidenceErrorKind.UNKNOWN -> operationFailed
    }
}
