package com.linan.barezen_drive.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.core.dto.ContentsResponse
import com.linan.barezen_drive.core.dto.S3BucketDto
import com.linan.barezen_drive.core.dto.WebdavTokenDto
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.data.s3.BucketNameProblem
import com.linan.barezen_drive.data.s3.S3Client
import com.linan.barezen_drive.data.s3.buildClientConfig
import com.linan.barezen_drive.data.s3.s3BucketNameOk
import com.linan.barezen_drive.data.s3.s3BucketNameProblem
import com.linan.barezen_drive.data.s3.s3ObjectKey
import com.linan.barezen_drive.platform.copyToClipboard
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.ui.component.EmptyState
import com.linan.barezen_drive.ui.glass.GlassCard
import com.linan.barezen_drive.ui.glass.GlassSectionHeader
import com.linan.barezen_drive.ui.theme.hairlineStroke
import com.linan.barezen_drive.ui.screens.files.formatFileSize
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha
import kotlinx.coroutines.CancellationException
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
    var bucketsLoadFailed by remember { mutableStateOf(false) }
    var tokensLoadFailed by remember { mutableStateOf(false) }
    var reloadAttempt by remember { mutableIntStateOf(0) }
    var pingMs by remember { mutableStateOf<Long?>(null) }
    var pinging by remember { mutableStateOf(true) }
    var pingAttempt by remember { mutableIntStateOf(0) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletingBucket by remember { mutableStateOf<S3BucketDto?>(null) }
    var browsingBucket by remember { mutableStateOf<S3BucketDto?>(null) }

    LaunchedEffect(pingAttempt) {
        pinging = true
        try {
            pingMs = repo.ping()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            pingMs = -1L
        } finally {
            pinging = false
        }
    }

    LaunchedEffect(reloadAttempt) {
        loading = true
        bucketsLoadFailed = false
        tokensLoadFailed = false
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
        bucketsLoadFailed = bucketsRes.isFailure
        tokensLoadFailed = tokensRes.isFailure
        loading = false
    }

    fun reload() {
        reloadAttempt++
    }

    fun retryPing() {
        pingAttempt++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(browsingBucket?.name ?: strings.storageConsoleTitle) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (browsingBucket != null) browsingBucket = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
    ) { padding ->
        val currentBucket = browsingBucket
        if (currentBucket != null) {
            BucketBrowser(
                repo = repo,
                bucket = currentBucket,
                onBack = { browsingBucket = null },
                snackbar = snackbar,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
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
                    0 -> ConnectionTab(
                        repo = repo,
                        username = username,
                        pingMs = pingMs,
                        pinging = pinging,
                        tokens = tokens,
                        loadFailed = tokensLoadFailed,
                        onOpenKeys = onOpenKeys,
                        onRetryPing = ::retryPing,
                        onRetryLoad = ::reload,
                        snackbar = snackbar,
                    )
                    1 -> BucketsTab(
                        buckets = buckets,
                        invalidFolders = invalidFolders,
                        rootFileCount = rootFileCount,
                        loading = loading,
                        loadFailed = bucketsLoadFailed,
                        onCreateBucket = { showCreateDialog = true },
                        onDeleteBucket = { bucket ->
                            deletingBucket = bucket
                            showDeleteDialog = true
                        },
                        onBrowseBucket = { browsingBucket = it },
                        onReload = ::reload,
                        snackbar = snackbar,
                    )
                    2 -> ClientsTab(
                        repo = repo,
                        buckets = buckets,
                        tokens = tokens,
                        loadFailed = tokensLoadFailed,
                        onRetryLoad = ::reload,
                        snackbar = snackbar,
                    )
                }
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
        var deleting by remember(bucket.folderId) { mutableStateOf(false) }
        var deleteError by remember(bucket.folderId) { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        fun dismiss() {
            if (!deleting) showDeleteDialog = false
        }

        AlertDialog(
            onDismissRequest = ::dismiss,
            title = { Text(strings.consoleBucketDelete) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.consoleBucketDeleteConfirm(bucket.name))
                    deleteError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        deleteError = null
                        scope.launch {
                            try {
                                val res = repo.deleteS3Bucket(bucket.folderId)
                                if (res.isSuccess) {
                                    showDeleteDialog = false
                                    reload()
                                    snackbar.showSnackbar(strings.consoleBucketDeleted)
                                } else {
                                    deleteError = res.exceptionOrNull()?.message ?: strings.operationFailed
                                }
                            } finally {
                                deleting = false
                            }
                        }
                    },
                ) {
                    if (deleting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(strings.consoleBucketDelete)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = ::dismiss, enabled = !deleting) {
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
    pingMs: Long?,
    pinging: Boolean,
    tokens: List<WebdavTokenDto>,
    loadFailed: Boolean,
    onOpenKeys: () -> Unit,
    onRetryPing: () -> Unit,
    onRetryLoad: () -> Unit,
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
        if (loadFailed) item { LoadFailure(onRetryLoad) }
        item {
            GlassCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassSectionHeader(strings.consoleTabConnection)
                    HorizontalDivider()

                    InfoRow(strings.consoleEndpointS3, endpoint) { copy(endpoint, strings.consoleEndpointS3) }
                    HorizontalDivider()
                    InfoRow(strings.consoleRegion, "us-east-1") { copy("us-east-1", strings.consoleRegion) }
                    HorizontalDivider()
                    InfoRow(strings.consolePathStyle, strings.consolePathStyleRequired)
                    HorizontalDivider()
                    InfoRow(strings.consoleEndpointDav, "$endpoint/dav") {
                        copy("$endpoint/dav", strings.consoleEndpointDav)
                    }
                    HorizontalDivider()
                    InfoRow(strings.consoleUsername, username) { copy(username, strings.consoleUsername) }
                    HorizontalDivider()

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            pinging -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            pingMs != null && pingMs >= 0 -> Icon(
                                Icons.Default.CheckCircle,
                                null,
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            else -> Icon(
                                Icons.Default.Error,
                                null,
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                pinging -> strings.consoleChecking
                                pingMs != null && pingMs >= 0 -> strings.consoleReachableMs(pingMs)
                                else -> strings.consoleUnreachable
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetryPing, enabled = !pinging) {
                            Text(strings.actionRetry)
                        }
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
private fun InfoRow(label: String, value: String, onCopy: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        if (onCopy != null) {
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, LocalStrings.current.consoleCopy)
            }
        }
    }
}

@Composable
private fun LoadFailure(onRetry: () -> Unit) {
    val strings = LocalStrings.current
    GlassCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Error, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text(
                strings.loadFailed,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text(strings.actionRetry) }
        }
    }
}

@Composable
private fun BucketsTab(
    buckets: List<S3BucketDto>,
    invalidFolders: List<S3BucketDto>,
    rootFileCount: Long,
    loading: Boolean,
    loadFailed: Boolean,
    onCreateBucket: () -> Unit,
    onDeleteBucket: (S3BucketDto) -> Unit,
    onBrowseBucket: (S3BucketDto) -> Unit,
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
        } else if (loadFailed && buckets.isEmpty() && invalidFolders.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Error,
                title = strings.loadFailed,
                subtitle = "",
                actionLabel = strings.actionRetry,
                onAction = onReload,
            )
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
                if (loadFailed) item { LoadFailure(onReload) }
                if (buckets.isNotEmpty()) {
                    items(buckets) { bucket ->
                        BucketRow(
                            bucket = bucket,
                            onCopy = { copy("s3://${bucket.name}") },
                            onBrowse = { onBrowseBucket(bucket) },
                            onDelete = { onDeleteBucket(bucket) },
                        )
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
private fun BucketRow(
    bucket: S3BucketDto,
    onCopy: () -> Unit,
    onBrowse: () -> Unit,
    onDelete: () -> Unit,
) {
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
                TextButton(onClick = onBrowse) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.consoleBucketBrowse)
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

private data class BrowseFolder(val id: String, val name: String)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BucketBrowser(
    repo: FilesRepository,
    bucket: S3BucketDto,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var path by remember(bucket.folderId) {
        mutableStateOf(listOf(BrowseFolder(bucket.folderId, bucket.name)))
    }
    var contents by remember(bucket.folderId) { mutableStateOf<ContentsResponse?>(null) }
    var loading by remember(bucket.folderId) { mutableStateOf(true) }
    var loadFailed by remember(bucket.folderId) { mutableStateOf(false) }
    var retryAttempt by remember(bucket.folderId) { mutableIntStateOf(0) }
    val current = path.last()

    fun goBack() {
        if (path.size > 1) path = path.dropLast(1) else onBack()
    }

    BackHandler(enabled = true, onBack = ::goBack)

    LaunchedEffect(current.id, retryAttempt) {
        loading = true
        loadFailed = false
        val res = repo.contents(current.id)
        if (res.isSuccess) {
            contents = res.getOrThrow()
        } else {
            loadFailed = true
        }
        loading = false
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = ::goBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.consoleBrowseBack)
            }
            Text(
                path.joinToString("/") { it.name },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()
        val page = contents
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            loadFailed -> EmptyState(
                icon = Icons.Default.Error,
                title = strings.loadFailed,
                subtitle = "",
                actionLabel = strings.actionRetry,
                onAction = { retryAttempt++ },
            )
            page == null || (page.folders.isEmpty() && page.files.isEmpty()) -> EmptyState(
                icon = Icons.Default.FolderOpen,
                title = strings.consoleBrowseEmpty,
                subtitle = "",
            )
            else -> {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(page.folders, key = { it.id }) { folder ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable { path = path + BrowseFolder(folder.id, folder.name) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Folder, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(folder.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    items(page.files, key = { it.id }) { file ->
                        val key = s3ObjectKey(bucket.name, path.drop(1).map { it.name }, file.name)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    formatFileSize(file.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    if (copyToClipboard("s3://$key")) {
                                        snackbar.showSnackbar(strings.consoleCopied)
                                    } else {
                                        snackbar.showSnackbar(strings.consoleCopyFailed)
                                    }
                                }
                            }) {
                                Icon(Icons.Default.ContentCopy, strings.consoleBucketCopyPath)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientsTab(
    repo: FilesRepository,
    buckets: List<S3BucketDto>,
    tokens: List<WebdavTokenDto>,
    loadFailed: Boolean,
    onRetryLoad: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()

    var selectedClient by remember { mutableStateOf(S3Client.RCLONE) }
    var selectedTokenId by remember { mutableStateOf<String?>(null) }
    var selectedBucketName by remember { mutableStateOf<String?>(buckets.firstOrNull()?.name) }

    val s3Tokens = tokens.filter { it.s3Ready }

    LaunchedEffect(s3Tokens) {
        if (s3Tokens.none { it.id == selectedTokenId }) {
            selectedTokenId = s3Tokens.firstOrNull()?.id
        }
    }
    LaunchedEffect(buckets) {
        selectedBucketName = selectedBucketName?.takeIf { current ->
            buckets.any { it.name == current }
        } ?: buckets.firstOrNull()?.name
    }

    fun copy(text: String) {
        scope.launch {
            if (copyToClipboard(text)) {
                snackbar.showSnackbar(strings.consoleConfigCopied)
            } else {
                snackbar.showSnackbar(strings.consoleCopyFailed)
            }
        }
    }

    if (loadFailed && tokens.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Error,
            title = strings.loadFailed,
            subtitle = "",
            actionLabel = strings.actionRetry,
            onAction = onRetryLoad,
        )
    } else if (tokens.isEmpty()) {
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
            if (loadFailed) item { LoadFailure(onRetryLoad) }
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

            if (buckets.isNotEmpty()) {
                item {
                    GlassCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            GlassSectionHeader(strings.consoleBucketLabel)
                            HorizontalDivider()

                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(selectedBucketName == null) { selectedBucketName = null }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selectedBucketName == null, null)
                                Spacer(Modifier.width(8.dp))
                                Text(strings.consoleBucketNone, style = MaterialTheme.typography.bodyLarge)
                            }
                            buckets.forEach { bucket ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .selectable(selectedBucketName == bucket.name) {
                                            selectedBucketName = bucket.name
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selectedBucketName == bucket.name, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(bucket.name, style = MaterialTheme.typography.bodyLarge)
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
                                    val config = buildClientConfig(
                                        selectedClient,
                                        repo.baseUrl,
                                        selectedTokenId!!,
                                        bucket = selectedBucketName,
                                    )
                                    copy(config)
                                }) {
                                    Icon(Icons.Default.ContentCopy, strings.consoleConfigCopy)
                                }
                            }

                            HorizontalDivider()

                            SelectionContainer {
                                Text(
                                    buildClientConfig(
                                        selectedClient,
                                        repo.baseUrl,
                                        selectedTokenId!!,
                                        bucket = selectedBucketName,
                                    ),
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
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    val problem = s3BucketNameProblem(name)
    val valid = name.isNotBlank() && s3BucketNameOk(name)

    fun dismiss() {
        if (!creating) onDismiss()
    }

    AlertDialog(onDismissRequest = ::dismiss) {
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
                    enabled = !creating,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                createError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = ::dismiss, enabled = !creating) { Text(strings.consoleBrowseBack) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (creating) return@Button
                            creating = true
                            createError = null
                            scope.launch {
                                try {
                                    val res = repo.createS3Bucket(name)
                                    if (res.isSuccess) {
                                        onCreated()
                                    } else {
                                        createError = res.exceptionOrNull()?.message ?: strings.operationFailed
                                    }
                                } finally {
                                    creating = false
                                }
                            }
                        },
                        enabled = valid && !creating,
                    ) {
                        if (creating) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(strings.consoleBucketCreate)
                        }
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
    BucketNameProblem.RESERVED -> strings.bucketNameReserved
}
