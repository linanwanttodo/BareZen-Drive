package com.linan.barezen_drive.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.Image
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.linan.barezen_drive.platform.rememberImagePicker
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.ui.glass.GlassCard
import com.linan.barezen_drive.ui.glass.GlassSectionHeader
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha
import com.linan.barezen_drive.platform.openInBrowser
import com.linan.barezen_drive.ui.theme.ThemeMode
import com.linan.barezen_drive.ui.theme.avatarColor
import com.linan.barezen_drive.ui.theme.avatarLetter
import com.linan.barezen_drive.i18n.LocalStrings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Google-style account avatar: a single solid-color circle filled with the
 * stable per-username color, showing the first letter of the name.
 */
@Composable
fun AccountAvatar(username: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .background(color = avatarColor(username), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            avatarLetter(username),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** One labeled settings entry row with optional trailing content. */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    username: String,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    languageMode: Int,
    onLanguageModeChange: (Int) -> Unit,
    accentColor: Color,
    onAccentColorChange: (Color) -> Unit,
    glassBlurEnabled: Boolean,
    onGlassBlurChange: (Boolean) -> Unit,
    glassAlphaPercent: Int,
    onGlassAlphaChange: (Int) -> Unit,
    serverUrl: String,
    ping: suspend () -> Long,
    currentVersion: String,
    checkUpdate: suspend () -> com.linan.barezen_drive.data.update.UpdateStatus,
    registrationOpen: Boolean?,
    onRegistrationOpenChange: (Boolean) -> Unit,
    wallpaperEnabled: Boolean,
    onWallpaperToggle: (Boolean) -> Unit,
    onPickWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenUsers: () -> Unit = {},
    onOpenShareManager: () -> Unit,
    onLogout: () -> Unit,
    onBack: (() -> Unit)? = null,
    files: com.linan.barezen_drive.data.repo.FilesRepository? = null,
    currentUserId: String? = null,
) {
    var showAccountInfo by remember { androidx.compose.runtime.mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
        topBar = {
            TopAppBar(
                title = { Text(LocalStrings.current.tabSettings) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = LocalStrings.current.actionBack,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState()),
        ) {
            GlassSectionHeader(LocalStrings.current.settingsAccount)
            GlassCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAccountInfo = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarPickerButton(files = files, username = username, userId = currentUserId)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(username.ifBlank { LocalStrings.current.notSignedIn }, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        LocalStrings.current.accountTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onLogout) { Text(LocalStrings.current.actionSignOut) }
            }
            }

            GlassSectionHeader(LocalStrings.current.settingsAppearance)
            GlassCard {
            SettingsRow(title = LocalStrings.current.settingsTheme, subtitle = LocalStrings.current.themeModeHint) {
                SingleChoiceSegmentedButtonRow {
                    val modes = listOf(
                        Triple(LocalStrings.current.themeLight, 0, ThemeMode.LIGHT),
                        Triple(LocalStrings.current.themeDark, 1, ThemeMode.DARK),
                        Triple(LocalStrings.current.themeSystem, 2, ThemeMode.SYSTEM),
                    )
                    modes.forEachIndexed { index, modeSpec ->
                        val (label, _, mode) = modeSpec
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                        ) { Text(label) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingsRow(title = LocalStrings.current.settingsLanguage, subtitle = LocalStrings.current.languageModeHint) {
                var languageOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
                val languageLabels = listOf(
                    LocalStrings.current.languageSystem,
                    LocalStrings.current.languageChinese,
                    LocalStrings.current.languageEnglish,
                )
                ExposedDropdownMenuBox(
                    expanded = languageOpen,
                    onExpandedChange = { languageOpen = it },
                    modifier = Modifier.width(140.dp),
                ) {
                    OutlinedTextField(
                        value = languageLabels.getOrElse(languageMode) { languageLabels[0] },
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = MaterialTheme.shapes.medium,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(languageOpen)
                        },
                        modifier = Modifier.menuAnchor().width(140.dp),
                    )
                    DropdownMenu(
                        expanded = languageOpen,
                        onDismissRequest = { languageOpen = false },
                    ) {
                        languageLabels.forEachIndexed { index, label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onLanguageModeChange(index)
                                    languageOpen = false
                                },
                            )
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingsRow(
                title = LocalStrings.current.settingsAccentColor,
                subtitle = LocalStrings.current.pickAccentColor,
            )
            // Full-width block BELOW the title row - putting the swatches in
            // the row's trailing slot squeezes them against the labels.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AccentSwatches.forEach { swatch ->
                    val selected = accentColor == swatch
                    val ringColor = MaterialTheme.colorScheme.primary
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .drawBehind {
                                if (selected) {
                                    drawCircle(
                                        ringColor,
                                        radius = size.minDimension / 2 + 3.dp.toPx(),
                                        style = Stroke(width = 2.dp.toPx()),
                                    )
                                }
                            }
                            .background(color = swatch, shape = CircleShape)
                            .clickable {
                                onAccentColorChange(swatch)
                            },
                        contentAlignment = Alignment.Center,
                    ) {}
                }
            }
            }

            GlassSectionHeader(LocalStrings.current.glassBottomBar)
            GlassCard {
            SettingsRow(
                title = LocalStrings.current.blurEffect,
                subtitle = LocalStrings.current.glassBottomBarOffHint,
                trailing = {
                    Switch(checked = glassBlurEnabled, onCheckedChange = onGlassBlurChange)
                },
            )
            SettingsRow(
                title = LocalStrings.current.surfaceOpacity,
                subtitle = LocalStrings.current.panelAlphaHint,
            ) {
                Text(
                    "$glassAlphaPercent%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = glassAlphaPercent.toFloat(),
                onValueChange = { onGlassAlphaChange(it.toInt().coerceIn(30, 91)) },
                valueRange = 30f..91f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            }

            GlassSectionHeader(LocalStrings.current.settingsWallpaper)
            GlassCard {
            SettingsRow(
                title = LocalStrings.current.wallpaperBackground,
                subtitle = LocalStrings.current.wallpaperHint,
                trailing = {
                    Switch(
                        checked = wallpaperEnabled,
                        onCheckedChange = onWallpaperToggle,
                    )
                },
            )
            if (wallpaperEnabled) {
                SettingsRow(
                    title = LocalStrings.current.pickWallpaper,
                    subtitle = LocalStrings.current.thumbnailAutoCompressHint,
                    onClick = onPickWallpaper,
                    trailing = { Icon(Icons.Default.Wallpaper, contentDescription = null) },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onClearWallpaper) { Text(LocalStrings.current.clearWallpaper) }
                }
            }
            }

            GlassSectionHeader(LocalStrings.current.settingsServer)
            GlassCard {
                SettingsRow(
                    title = LocalStrings.current.userManagement,
                    subtitle = LocalStrings.current.userManagementHint,
                    onClick = onOpenUsers,
                    trailing = { Icon(Icons.Default.Person, contentDescription = null) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                if (registrationOpen != null) {
                    SettingsRow(
                        title = LocalStrings.current.openRegistration,
                        subtitle = if (registrationOpen) LocalStrings.current.openRegistrationOnHint
                        else LocalStrings.current.openRegistrationOffHint,
                        trailing = {
                            Switch(checked = registrationOpen, onCheckedChange = onRegistrationOpenChange)
                        },
                    )
                }
            }

            GlassSectionHeader(LocalStrings.current.actionShare)
            GlassCard {
            SettingsRow(
                title = LocalStrings.current.shareManagement,
                subtitle = LocalStrings.current.shareManagerSubtitle,
                onClick = onOpenShareManager,
                trailing = { Icon(Icons.Default.Link, contentDescription = null) },
            )
            }

            Spacer(Modifier.height(16.dp))

            GlassSectionHeader(LocalStrings.current.settingsAbout)
            GlassCard {
            UpdateCheckRow(
                currentVersion = currentVersion,
                checkUpdate = checkUpdate,
            )
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingsRow(
                title = LocalStrings.current.openSourceNotices,
                subtitle = LocalStrings.current.openSourceComponentsTitle,
                onClick = onOpenSource,
                trailing = { Icon(Icons.Default.Info, contentDescription = null) },
            )

            }

            Spacer(Modifier.height(16.dp))
            GlassCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openInBrowser(GITHUB_PROFILE_URL) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    LocalStrings.current.copyrightHolder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.Code,
                    contentDescription = LocalStrings.current.openGitHubProfile,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            }
            // Bottom clearance for the floating glass bar (content flows behind).
            Spacer(Modifier.height(112.dp))
        }

        if (showAccountInfo) {
            AccountInfoDialog(
                username = username,
                serverUrl = serverUrl,
                ping = ping,
                onDismiss = { showAccountInfo = false },
            )
        }
    }
}

/**
 * Account info dialog: shows the server address and username used at login.
 * The password is never stored on the client (the server only keeps a BCrypt
 * hash), so it cannot be shown again; the session is kept alive by the tokens.
 */
@Composable
private fun AccountInfoDialog(
    username: String,
    serverUrl: String,
    ping: suspend () -> Long,
    onDismiss: () -> Unit,
) {
    var latency by remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    var pingFailed by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { latency = ping() }.onFailure { pingFailed = true }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(LocalStrings.current.accountInfo) },
        text = {
            Column {
                InfoRow(LocalStrings.current.fieldUsername, username.ifBlank { LocalStrings.current.notSignedIn })
                InfoRow(LocalStrings.current.fieldServerUrl, serverUrl.ifBlank { LocalStrings.current.valueNotSet })
                InfoRow(
                    LocalStrings.current.statusReachable,
                    when {
                        pingFailed -> LocalStrings.current.statusUnreachable
                        latency != null -> "${latency}ms"
                        else -> LocalStrings.current.measuring
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    LocalStrings.current.passwordPolicyNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(LocalStrings.current.actionClose) }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private const val GITHUB_PROFILE_URL = "https://github.com/linanwanttodo"

private val AccentSwatches = listOf(
    Color(0xFF0969DA), // GitHub blue
    Color(0xFFF27297), // NPatch pink
    Color(0xFF1A7F37), // green
    Color(0xFF8250DF), // purple
    Color(0xFFBC4C00), // orange
    Color(0xFF1B7C83), // teal
    Color(0xFFCF222E), // red
)

/** Account avatar with an upload path: tap "change avatar" to pick an image. */
@Composable
private fun AvatarPickerButton(
    files: com.linan.barezen_drive.data.repo.FilesRepository?,
    username: String,
    userId: String?,
) {
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }
    val bmp by com.linan.barezen_drive.ui.AvatarStore.bitmap.collectAsState()
    val picker = rememberImagePicker { picks ->
        val pick = picks.firstOrNull() ?: return@rememberImagePicker
        if (files == null) return@rememberImagePicker
        uploading = true
        scope.launch {
            val bytes = runCatching { pick.readRange(0, pick.size.toInt()) }.getOrNull()
            val ok = bytes != null && files.putAvatar(bytes).isSuccess
            if (ok) {
                com.linan.barezen_drive.ui.AvatarStore.invalidate()
                com.linan.barezen_drive.ui.AvatarStore.ensure(files, userId)
            }
            uploading = false
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            val image = bmp
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = LocalStrings.current.avatarChange,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .androidxCircleClip(),
                )
            } else {
                AccountAvatar(username)
            }
            if (uploading) {
                CircularProgressIndicator(Modifier.size(46.dp), strokeWidth = 2.dp)
            }
        }
        if (files != null) {
            TextButton(onClick = { picker() }) {
                Text(LocalStrings.current.avatarChange, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Circle clip helper kept tiny for the avatar image. */
private fun Modifier.androidxCircleClip(): Modifier = this.then(
    Modifier.clip(androidx.compose.foundation.shape.CircleShape),
)
