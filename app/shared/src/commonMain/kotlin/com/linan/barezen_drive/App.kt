package com.linan.barezen_drive

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.ImageBitmap
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.FolderDto
import com.linan.barezen_drive.data.api.ApiClient
import com.linan.barezen_drive.data.local.AppPreferences
import com.linan.barezen_drive.data.local.TokenStorage
import com.linan.barezen_drive.data.repo.AuthRepository
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.data.upload.UploadManager
import com.linan.barezen_drive.ui.screens.album.AlbumScreen
import com.linan.barezen_drive.ui.screens.files.FilesScreen
import com.linan.barezen_drive.ui.screens.home.HomeScreen
import com.linan.barezen_drive.ui.screens.login.LoginScreen
import com.linan.barezen_drive.ui.screens.preview.PreviewScreen
import com.linan.barezen_drive.ui.screens.settings.OpenSourceScreen
import com.linan.barezen_drive.ui.screens.settings.SettingsScreen
import com.linan.barezen_drive.ui.screens.settings.WebUpdatePrompt
import com.linan.barezen_drive.ui.media.ThumbnailLoader
import com.linan.barezen_drive.ui.shell.MainShell
import com.linan.barezen_drive.ui.shell.MainTab
import com.linan.barezen_drive.ui.theme.AppTheme
import com.linan.barezen_drive.ui.theme.DefaultSeed
import com.linan.barezen_drive.ui.theme.LocalCardAlpha
import com.linan.barezen_drive.ui.theme.LocalPanelAlpha
import com.linan.barezen_drive.ui.theme.ThemeMode
import com.linan.barezen_drive.ui.theme.wallpaperSeedColor
import com.linan.barezen_drive.ui.wallpaper.WallpaperImage
import com.linan.barezen_drive.ui.wallpaper.loadPersistedWallpaper
import com.linan.barezen_drive.ui.wallpaper.rememberWallpaperPicker
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.linan.barezen_drive.platform.isWebPlatform
import com.linan.barezen_drive.platform.systemAccentColor
import com.linan.barezen_drive.platform.initialShareToken
import com.linan.barezen_drive.platform.rememberFileSaver
import com.linan.barezen_drive.ui.screens.share.ShareScreen
import com.linan.barezen_drive.ui.screens.share.ShareManagerScreen
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.i18n.Language
import com.linan.barezen_drive.i18n.LocalStrings
import com.linan.barezen_drive.i18n.stringsFor
import com.linan.barezen_drive.platform.systemLanguageTag

/**
 * Minimal navigation backstack (no navigation library): a list of sealed
 * screens where the last element is the visible one. The three app tabs
 * (recent / files / settings) live in MainShell and are not part of the
 * backstack; only full-screen flows (preview, open-source credits) push.
 * DI is deliberately minimal: ApiClient / repositories / UploadManager are
 * created once inside remember {} at the App root and passed down as
 * constructor parameters.
 */
private sealed interface Screen {
    data object Login : Screen
    data object Main : Screen
    data class Preview(val files: List<FileDto>, val index: Int) : Screen
    data object OpenSource : Screen
    data object ShareManager : Screen
}

/** /s/<token> public share entry captured once at startup; null on non-web. */
private val shareEntryToken: String? by lazy { initialShareToken() }

private fun themeModeFromIndex(index: Int): ThemeMode = when (index) {
    1 -> ThemeMode.LIGHT
    2 -> ThemeMode.DARK
    else -> ThemeMode.SYSTEM
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun App() {
    // Preferences are read once per composition root; writes go through the
    // same AppPreferences object so every screen sees consistent values.
    val prefs = remember { AppPreferences.get() }
    var themeMode by remember { mutableStateOf(themeModeFromIndex(prefs.themeMode)) }
    var wallpaperEnabled by remember { mutableStateOf(prefs.wallpaperEnabled) }
    var wallpaper by remember { mutableStateOf<WallpaperImage?>(null) }
    var wallpaperBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var username by remember { mutableStateOf(prefs.username) }
    var languageMode by remember { mutableStateOf(prefs.languageMode) }
    // 0 = follow the system, otherwise the user's explicit choice. The resolved
    // language drives both the composition-local lookup used by composables and
    // the process-wide accessor used by non-composable code.
    val language = Language.fromMode(languageMode) ?: Language.fromTag(systemLanguageTag())
    if (I18n.language != language) {
        I18n.set(language)
    }

    // Load the persisted wallpaper (if any) once; re-decode only on change.
    LaunchedEffect(wallpaperEnabled) {
        if (wallpaper == null) {
            wallpaper = loadPersistedWallpaper()
        }
    }
    LaunchedEffect(wallpaper) {
        wallpaperBitmap = wallpaper?.bitmap()
    }

    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Accent seed priority: wallpaper (if shown) > system dynamic > user pick > default.
    val systemAccent = remember { systemAccentColor() }
    var accentColor by remember { mutableStateOf(if (prefs.accentColor != 0) Color(prefs.accentColor) else DefaultSeed) }
    var useDynamicColor by remember { mutableStateOf(prefs.useDynamicColor) }
    var glassBlur by remember { mutableStateOf(prefs.glassBlurEnabled) }
    var glassAlpha by remember { mutableStateOf(prefs.glassAlphaPercent) }
    val accentSeed = when {
        useDynamicColor && systemAccent != null -> systemAccent
        else -> accentColor
    }
    val effectiveSeed = wallpaperSeedColor(wallpaper.takeIf { wallpaperEnabled }) ?: accentSeed

    AppTheme(themeMode = themeMode, seed = effectiveSeed) {
        CompositionLocalProvider(
            LocalStrings provides stringsFor(language),
            LocalPanelAlpha provides (if (wallpaperEnabled || wallpaperBitmap != null) 0.6f else 1f),
            LocalCardAlpha provides (glassAlpha.coerceIn(30, 91) / 100f),
        ) {
        // Theme-colored root: every screen (login included) inherits the correct
        // background instead of the platform default white.
        val scheme = MaterialTheme.colorScheme
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .background(scheme.background),
        ) {
        // Single instances for the whole app lifetime of the composition.
        val api = remember { ApiClient() }
        val auth = remember { AuthRepository(api, TokenStorage) }
        val files = remember { FilesRepository(api) }
        val uploader = remember { UploadManager(files) }
        val thumbs = remember { ThumbnailLoader(files) }
        // Server-side registration switch: null until the first load answers,
        // which hides the toggle offline and on servers without the endpoint.
        var registrationOpen by remember { mutableStateOf<Boolean?>(null) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            registrationOpen = files.registrationStatus().getOrNull()?.open
        }

        var stack by remember {
            mutableStateOf(
                listOf<Screen>(
                    if (TokenStorage.accessToken != null) Screen.Main else Screen.Login,
                ),
            )
        }
        // Public share pages bypass login and the tab shell entirely.
        val shareToken = shareEntryToken
        if (shareToken != null) {
            Box(Modifier.fillMaxSize().background(scheme.background)) {
                ShareScreen(
                    token = shareToken,
                    repo = files,
                    onExit = { stack = listOf(Screen.Login) },
                )
            }
            return@Box
        }
        var tab by remember { mutableStateOf(MainTab.HOME) }
        var filesPath by remember { mutableStateOf(emptyList<FolderDto>()) }
        val current = stack.last()
        val push: (Screen) -> Unit = { stack = stack + it }
        val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
        // System back pops the in-app backstack instead of exiting the app.
        BackHandler(enabled = stack.size > 1) { pop() }

        val wallpaperPicker = rememberWallpaperPicker { img ->
            wallpaper = img
            wallpaperEnabled = img != null
            prefs.wallpaperEnabled = img != null
        }
        // Web-only quick theme toggle for the top-right corner: flips between
        // explicit light/dark (resolving from the effective scheme when in
        // SYSTEM mode). Other platforms keep the settings-screen selector.
        val themeToggle: (@Composable () -> Unit)? = if (isWebPlatform()) {
            {
                IconButton(onClick = {
                    val next = if (dark) ThemeMode.LIGHT else ThemeMode.DARK
                    themeMode = next
                    prefs.themeMode = next.ordinal
                }) {
                    Icon(
                        if (dark) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = LocalStrings.current.toggleTheme,
                    )
                }
            }
        } else {
            null
        }
        // One shared saver for the home tab; the files screen owns its own so
        // download failures surface through that screen's snackbar.
        val recentSaver = rememberFileSaver { ok -> }
        // Browser clients loaded before a server upgrade keep running the old
        // bundle; this offers the reload that pulls the new one from the server.
        WebUpdatePrompt(
            currentVersion = com.linan.barezen_drive.core.BuildInfo.VERSION,
            enabled = isWebPlatform() && files.baseUrl.isNotBlank(),
            checkUpdate = { com.linan.barezen_drive.data.update.UpdateChecker.check(files) },
        )

        when (current) {
            is Screen.Login -> LoginScreen(
                auth = auth,
                onLoggedIn = {
                    username = prefs.username
                    stack = listOf(Screen.Main)
                },
                themeToggle = themeToggle,
            )
            is Screen.Main -> {
                MainShell(
                    selected = tab,
                    onSelect = { tab = it },
                    wallpaperBitmap = wallpaperBitmap.takeIf { wallpaperEnabled },
                ) {
                    val wallpaperBehind = wallpaperEnabled && wallpaperBitmap != null
                    when (tab) {
                        MainTab.HOME -> HomeScreen(
                            repo = files,
                            thumbs = thumbs,
                            onOpenAlbum = { tab = MainTab.ALBUM },
                            onPreview = { fs, idx -> push(Screen.Preview(fs, idx)) },
                            saver = recentSaver,
                            themeToggle = themeToggle,
                        )
                        MainTab.ALBUM -> AlbumScreen(
                            repo = files,
                            thumbs = thumbs,
                            uploader = uploader,
                            onBack = null,
                            onPreview = { fs, idx -> push(Screen.Preview(fs, idx)) },
                        )
                        MainTab.FILES -> FilesScreen(                            path = filesPath,
                            repo = files,
                            uploader = uploader,
                            auth = auth,
                            thumbs = thumbs,
                            wallpaperBehind = wallpaperBehind,
                            onOpenFolder = { filesPath = filesPath + it },
                            onJumpTo = { idx -> filesPath = filesPath.take(idx + 1) },
                            onPreview = { fs, idx -> push(Screen.Preview(fs, idx)) },
                            onLoggedOut = {
                                username = ""
                                prefs.username = ""
                                auth.logout()
                                stack = listOf(Screen.Login)
                            },
                            themeToggle = themeToggle,
                        )
                        MainTab.SETTINGS -> SettingsScreen(
                            username = username,
                            themeMode = themeMode,
                            onThemeModeChange = { m ->
                                themeMode = m
                                prefs.themeMode = m.ordinal
                            },
                            languageMode = languageMode,
                            onLanguageModeChange = { m ->
                                languageMode = m
                                prefs.languageMode = m
                            },
                            accentColor = accentColor,
                            onAccentColorChange = { c ->
                                accentColor = c
                                prefs.accentColor = c.toArgb()
                                useDynamicColor = false
                                prefs.useDynamicColor = false
                            },
                            useDynamicColor = useDynamicColor,
                            systemAccent = systemAccent,
                            onDynamicColorChange = { on ->
                                useDynamicColor = on
                                prefs.useDynamicColor = on
                            },                            glassBlurEnabled = glassBlur,
                            onGlassBlurChange = { on ->
                                glassBlur = on
                                prefs.glassBlurEnabled = on
                            },
                            glassAlphaPercent = glassAlpha,
                            onGlassAlphaChange = { v ->
                                glassAlpha = v
                                prefs.glassAlphaPercent = v
                            },
                            serverUrl = files.baseUrl,
                            ping = { files.ping() },
                            currentVersion = com.linan.barezen_drive.core.BuildInfo.VERSION,
                            checkUpdate = { com.linan.barezen_drive.data.update.UpdateChecker.check(files) },
                            registrationOpen = registrationOpen,
                            onRegistrationOpenChange = { open ->
                                // Optimistic flip; the server answer is the truth.
                                registrationOpen = open
                                scope.launch {
                                    registrationOpen = files.setRegistrationOpen(open)
                                        .getOrNull()?.open ?: open
                                }
                            },
                            wallpaperEnabled = wallpaperEnabled,
                            onWallpaperToggle = { on ->
                                wallpaperEnabled = on
                                prefs.wallpaperEnabled = on
                            },
                            onPickWallpaper = { wallpaperPicker() },
                            onClearWallpaper = {
                                wallpaper = null
                                wallpaperBitmap = null
                                wallpaperEnabled = false
                                prefs.wallpaperEnabled = false
                            },
                            onOpenSource = { push(Screen.OpenSource) },
                            onOpenShareManager = { push(Screen.ShareManager) },
                            onLogout = {
                                username = ""
                                prefs.username = ""
                                auth.logout()
                                stack = listOf(Screen.Login)
                            },
                        )
                    }
                }
                // Registered after the shell so folder-back wins while enabled.
                BackHandler(enabled = tab == MainTab.FILES && filesPath.isNotEmpty()) {
                    filesPath = filesPath.dropLast(1)
                }
            }
            is Screen.Preview -> PreviewScreen(
                files = current.files,
                initialIndex = current.index,
                repo = files,
                onBack = pop,
            )
            is Screen.OpenSource -> OpenSourceScreen(onBack = pop)
            is Screen.ShareManager -> ShareManagerScreen(repo = files, onBack = pop)
        }
        }
    }
    }
}
