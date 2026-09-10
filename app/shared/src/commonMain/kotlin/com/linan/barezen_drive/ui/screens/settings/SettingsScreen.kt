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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.ui.glass.GlassCard
import com.linan.barezen_drive.ui.glass.GlassSectionHeader
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha
import com.linan.barezen_drive.platform.isAndroidPlatform
import com.linan.barezen_drive.platform.openInBrowser
import com.linan.barezen_drive.ui.theme.ThemeMode
import com.linan.barezen_drive.ui.theme.avatarColor
import com.linan.barezen_drive.ui.theme.avatarLetter
import com.linan.barezen_drive.i18n.LocalStrings

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

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    username: String,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    languageMode: Int,
    onLanguageModeChange: (Int) -> Unit,
    accentColor: Color,
    onAccentColorChange: (Color) -> Unit,
    useDynamicColor: Boolean,
    systemAccent: Color?,
    onDynamicColorChange: (Boolean) -> Unit,
    glassBlurEnabled: Boolean,
    onGlassBlurChange: (Boolean) -> Unit,
    glassAlphaPercent: Int,
    onGlassAlphaChange: (Int) -> Unit,
    serverUrl: String,
    ping: suspend () -> Long,
    wallpaperEnabled: Boolean,
    onWallpaperToggle: (Boolean) -> Unit,
    onPickWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenShareManager: () -> Unit,
    onLogout: () -> Unit,
) {
    var showAccountInfo by remember { androidx.compose.runtime.mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = LocalPanelAlpha.current),
        topBar = {
            TopAppBar(
                title = { Text(LocalStrings.current.tabSettings) },
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
                AccountAvatar(username)
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
                SingleChoiceSegmentedButtonRow {
                    val languageModes = listOf(
                        Triple(LocalStrings.current.languageSystem, 0, 0),
                        Triple(LocalStrings.current.languageChinese, 1, 1),
                        Triple(LocalStrings.current.languageEnglish, 2, 2),
                    )
                    languageModes.forEachIndexed { index, languageSpec ->
                        val (label, _, mode) = languageSpec
                        SegmentedButton(
                            selected = languageMode == mode,
                            onClick = { onLanguageModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = languageModes.size),
                        ) { Text(label) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            if (systemAccent != null) {
                SettingsRow(
                    title = LocalStrings.current.dynamicColor,
                    subtitle = LocalStrings.current.monetHint,
                    trailing = {
                        Switch(checked = useDynamicColor, onCheckedChange = onDynamicColorChange)
                    },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            SettingsRow(
                title = LocalStrings.current.settingsAccentColor,
                subtitle = if (useDynamicColor) LocalStrings.current.dynamicColorOverridesAccent else LocalStrings.current.pickAccentColor,
            )
            // Full-width block BELOW the title row - putting the swatches in
            // the row's trailing slot squeezes them against the labels.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                AccentSwatches.forEach { swatch ->
                    val selected = !useDynamicColor && accentColor == swatch
                    val ringColor = MaterialTheme.colorScheme.primary
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .drawBehind {
                                if (selected) {
                                    drawCircle(
                                        ringColor,
                                        radius = size.minDimension / 2 + 3.dp.toPx(),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
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
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                ) {
                    TextButton(onClick = onClearWallpaper) { Text(LocalStrings.current.clearWallpaper) }
                }
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
            if (isAndroidPlatform) {
                UpdateCheckRow()
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
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
 * 账号信息弹窗：展示登录时使用的服务器地址与用户名。密码不在客户端保存
 * （服务器侧只存 BCrypt 哈希），因此无法回显；会话通过 token 维持。
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

    androidx.compose.material3.AlertDialog(
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
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(LocalStrings.current.actionClose) }
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
    Color(0xFF6639BA), // indigo
)
