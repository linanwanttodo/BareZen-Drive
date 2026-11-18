package com.linan.barezen_drive.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.S3BucketDto
import com.linan.barezen_drive.core.dto.WebdavTokenDto
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.data.s3.BucketNameProblem
import com.linan.barezen_drive.data.s3.S3Client
import com.linan.barezen_drive.data.s3.buildClientConfig
import com.linan.barezen_drive.data.s3.s3BucketNameOk
import com.linan.barezen_drive.data.s3.s3BucketNameProblem
import com.linan.barezen_drive.platform.copyToClipboard
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.ui.component.EmptyState
import com.linan.barezen_drive.ui.glass.GlassCard
import com.linan.barezen_drive.ui.glass.GlassSectionHeader
import com.linan.barezen_drive.ui.theme.hairlineStroke
import com.linan.barezen_drive.ui.screens.files.formatFileSize
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageConsoleScreen(
    repo: FilesRepository,
    username: String,
    onBack: () -> Unit,
    onOpenKeys: () -> Unit,
) {
    val strings = LocalStrings.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var buckets by remember { mutableStateOf<List<S3BucketDto>>(emptyList()) }
    var invalidFolders by remember { mutableStateOf<List<S3BucketDto>>(emptyList()) }
    var rootFileCount by remember { mutableLongStateOf(0L) }
    var tokens by remember { mutableStateOf<List<WebdavTokenDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var pingMs by remember { mutableLongStateOf(-1L) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletingBucket by remember { mutableStateOf<S3BucketDto?>(null) }

    LaunchedEffect(Unit) {
        while (isActive) {
            val start = 0L
            try {
                pingMs = repo.ping()
            } catch (_: Exception) {
                pingMs = -1L
            }
            val elapsed = 0L
            delay((3000L - elapsed).coerceAtLeast(0L))
        }
    }

    LaunchedEffect(Unit) {
        loading = true
        val bucketsRes = repo.s3Buckets()
        if (bucketsRes.isSuccess) {
            val resp = bucketsRes.getOrThrow()
            buckets = resp.buckets.filter { it.s3Valid }.sortedBy { it.name.lowercase() }
            invalidFolders = resp.buckets.filter { !it.s3Valid }.sortedBy { it.name.lowercase() }
            rootFileCount = resp.rootFileCount
        }
        val tokensRes = repo.listWebdavTokens()
        if (tokensRes.isSuccess) {
            tokens = tokensRes.getOrThrow().tokens
        }
        loading = false
    }

    fun reload() {
        scope.launch {
            val bucketsRes = repo.s3Buckets()
            if (bucketsRes.isSuccess) {
                val resp = bucketsRes.getOrThrow()
                buckets = resp.buckets.filter { it.s3Valid }.sortedBy { it.name.lowercase() }
                invalidFolders = resp.buckets.filter { !it.s3Valid }.sortedBy { it.name.lowercase() }
                rootFileCount = resp.rootFileCount
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.storageConsoleTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(strings.consoleTabConnection) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(strings.consoleTabBuckets) },
                )
                Tab(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    text = { Text(strings.consoleTabClients) },
                )
            }

            when (tab) {
                0 -> ConnectionTab(repo, username, pingMs, tokens, onOpenKeys, snackbar)
                1 -> BucketsTab(
                    buckets,
                    invalidFolders,
                    rootFileCount,
                    loading,
                    onCreateBucket = { showCreateDialog = true },
                    onDeleteBucket = { bucket ->
                        deletingBucket = bucket
                        showDeleteDialog = true
                    },
                    onReload = ::reload,
                    snackbar,
                )
                2 -> ClientsTab(repo, tokens, snackbar)
            }
        }
    }

    if (showCreateDialog) {
        CreateBucketDialog(
            repo = repo,
            onDismiss = { showCreateDialog = false },
            onCreated = {
                showCreateDialog = false
                reload()
                scope.launch { snackbar.showSnackbar(strings.consoleBucketCreated) }
            },
        )
    }

    if (showDeleteDialog && deletingBucket != null) {
        val bucket = deletingBucket!!
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(strings.consoleBucketDelete) },
            text = { Text(strings.consoleBucketDeleteConfirm(bucket.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            if (bucket.objectCount > 0) {
                                snackbar.showSnackbar(strings.consoleBucketNotEmpty)
                                return@launch
                            }
                            val res = repo.deleteFolder(bucket.folderId)
                            if (res.isSuccess) {
                                snackbar.showSnackbar(strings.consoleBucketDeleted)
                                reload()
                            } else {
                                snackbar.showSnackbar(res.exceptionOrNull()?.message ?: "Error")
                            }
                        }
                    }
                ) { Text(strings.consoleBucketDelete) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(strings.consoleBrowseBack)
                }
            },
        )
    }
}

@Composable
private fun ConnectionTab(
    repo: FilesRepository,
    username: String,
    pingMs: Long,
    tokens: List<WebdavTokenDto>,
    onOpenKeys: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val endpoint = repo.baseUrl

    fun copy(text: String, label: String) {
        scope.launch {
            if (copyToClipboard(text)) {
                snackbar.showSnackbar("$label ${strings.consoleCopied}")
            } else {
                snackbar.showSnackbar(strings.consoleCopyFailed)
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            GlassCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassSectionHeader(strings.consoleTabConnection)
                    HorizontalDivider()

                    InfoRow(strings.consoleEndpointS3, endpoint) { copy(endpoint, strings.consoleEndpointS3) }
                    HorizontalDivider()
                    InfoRow(strings.consoleRegion, "us-east-1") { copy("us-east-1", strings.consoleRegion) }
                    HorizontalDivider()
                    InfoRow(strings.consolePathStyle, strings.consolePathStyleRequired) { }
                    HorizontalDivider()
                    InfoRow(strings.consoleEndpointDav, "$endpoint/dav") {
                        copy("$endpoint/dav", strings.consoleEndpointDav)
                    }
                    HorizontalDivider()
                    InfoRow(strings.consoleUsername, username) { copy(username, strings.consoleUsername) }
                    HorizontalDivider()

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (pingMs >= 0) Icons.Default.CheckCircle else Icons.Default.Error,
                            null,
                            Modifier.size(20.dp),
                            tint = if (pingMs >= 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (pingMs >= 0) strings.consoleReachableMs(pingMs) else strings.consoleUnreachable,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            GlassCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassSectionHeader(strings.consoleKeysTitle)
                    HorizontalDivider()

                    val s3Ready = tokens.count { it.s3Ready }
                    Text(
                        strings.consoleKeysSummary(tokens.size, s3Ready),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onOpenKeys,
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(strings.consoleManageKeys)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        if (onCopy != {}) {
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, LocalStrings.current.consoleCopy)
            }
        }
    }
}

@Composable
private fun BucketsTab(
    buckets: List<S3BucketDto>,
    invalidFolders: List<S3BucketDto>,
    rootFileCount: Long,
    loading: Boolean,
    onCreateBucket: () -> Unit,
    onDeleteBucket: (S3BucketDto) -> Unit,
    onReload: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()

    fun copy(text: String) {
        scope.launch {
            if (copyToClipboard(text)) {
                snackbar.showSnackbar(strings.consoleCopied)
            } else {
                snackbar.showSnackbar(strings.consoleCopyFailed)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else if (buckets.isEmpty() && invalidFolders.isEmpty()) {
            EmptyState(
                icon = Icons.Default.FolderOpen,
                title = strings.consoleNoBuckets,
                subtitle = strings.consoleNoBucketsHint,
                actionLabel = strings.consoleBucketCreate,
                onAction = onCreateBucket,
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (buckets.isNotEmpty()) {
                    items(buckets) { bucket ->
                        BucketRow(bucket, onCopy = { copy("s3://${bucket.name}") }, onDelete = { onDeleteBucket(bucket) })
                    }
                }

                if (invalidFolders.isNotEmpty()) {
                    item {
                        GlassCard {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlassSectionHeader(strings.consoleInvalidFolders)
                                Text(
                                    strings.consoleInvalidFoldersHint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                HorizontalDivider()
                                invalidFolders.forEach { folder ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.Folder, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(folder.name, style = MaterialTheme.typography.bodyLarge)
                                            val problem = s3BucketNameProblem(folder.name)
                                            if (problem != null) {
                                                Text(
                                                    problemHint(problem, strings),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    }
                                    if (folder != invalidFolders.last()) HorizontalDivider()
                                }
                            }
                        }
                    }
                }

                if (rootFileCount > 0) {
                    item {
                        GlassCard {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    strings.consoleRootFiles(rootFileCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item {
                    Button(
                        onClick = onCreateBucket,
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(strings.consoleBucketCreate)
                    }
                }
            }
        }
    }
}

@Composable
private fun BucketRow(bucket: S3BucketDto, onCopy: () -> Unit, onDelete: () -> Unit) {
    val strings = LocalStrings.current
    GlassCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(bucket.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        strings.consoleBucketStats(bucket.objectCount, formatFileSize(bucket.totalBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.consoleBucketCopyPath)
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.consoleBucketDelete, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ClientsTab(repo: FilesRepository, tokens: List<WebdavTokenDto>, snackbar: SnackbarHostState) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()

    var selectedClient by remember { mutableStateOf(S3Client.RCLONE) }
    var selectedTokenId by remember { mutableStateOf<String?>(null) }

    val s3Tokens = tokens.filter { it.s3Ready }

    fun copy(text: String) {
        scope.launch {
            if (copyToClipboard(text)) {
                snackbar.showSnackbar(strings.consoleConfigCopied)
            } else {
                snackbar.showSnackbar(strings.consoleCopyFailed)
            }
        }
    }

    if (tokens.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Key,
            title = strings.consoleNoKeys,
            subtitle = "",
        )
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                GlassCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassSectionHeader(strings.consoleClientLabel)
                        HorizontalDivider()

                        S3Client.entries.forEach { client ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(selectedClient == client) { selectedClient = client }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selectedClient == client, null)
                                Spacer(Modifier.width(8.dp))
                                Text(client.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }

            item {
                GlassCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassSectionHeader(strings.consoleKeyLabel)
                        HorizontalDivider()

                        if (s3Tokens.isEmpty()) {
                            Text(
                                strings.consoleS3NotReady,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            s3Tokens.forEach { token ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .selectable(selectedTokenId == token.id) { selectedTokenId = token.id }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selectedTokenId == token.id, null)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(token.label, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            token.id,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (s3Tokens.isNotEmpty() && selectedTokenId != null) {
                item {
                    GlassCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    strings.consoleConfigCopy,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                IconButton(onClick = {
                                    val config = buildClientConfig(selectedClient, repo.baseUrl, selectedTokenId!!)
                                    copy(config)
                                }) {
                                    Icon(Icons.Default.ContentCopy, strings.consoleConfigCopy)
                                }
                            }

                            HorizontalDivider()

                            SelectionContainer {
                                Text(
                                    buildClientConfig(selectedClient, repo.baseUrl, selectedTokenId!!),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }

                            HorizontalDivider()

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    strings.consoleSecretNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateBucketDialog(
    repo: FilesRepository,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    val problem = s3BucketNameProblem(name)
    val valid = name.isNotBlank() && s3BucketNameOk(name)

    AlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            border = hairlineStroke(),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(strings.consoleBucketCreate, style = MaterialTheme.typography.titleLarge)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(strings.consoleBucketNameLabel) },
                    supportingText = {
                        if (problem != null) {
                            Text(problemHint(problem, strings), color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(strings.consoleBucketNameHint)
                        }
                    },
                    isError = problem != null && name.isNotBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(strings.consoleBrowseBack) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                val res = repo.createFolder(null, name)
                                if (res.isSuccess) onCreated()
                            }
                        },
                        enabled = valid,
                    ) {
                        Text(strings.consoleBucketCreate)
                    }
                }
            }
        }
    }
}

private fun problemHint(problem: BucketNameProblem, strings: com.linan.barezen_drive.i18n.Strings): String = when (problem) {
    BucketNameProblem.TOO_SHORT -> strings.bucketNameTooShort
    BucketNameProblem.TOO_LONG -> strings.bucketNameTooLong
    BucketNameProblem.UPPERCASE -> strings.bucketNameUppercase
    BucketNameProblem.INVALID_CHARS -> strings.bucketNameInvalidChars
    BucketNameProblem.BAD_EDGE -> strings.bucketNameBadEdge
    BucketNameProblem.DOUBLE_DOT -> strings.bucketNameDoubleDot
    BucketNameProblem.IP_SHAPE -> strings.bucketNameIpShape
}
