package com.linan.barezen_drive.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.linan.barezen_drive.ui.glass.LiquidBottomTab
import com.linan.barezen_drive.ui.glass.LiquidBottomTabs
import com.linan.barezen_drive.i18n.LocalStrings
import androidx.compose.ui.graphics.ImageBitmap

/**
 * App-wide tab destinations, in fixed order: home (album + recent), file
 * browser, settings.
 */
enum class MainTab {
    HOME,
    ALBUM,
    FILES,
    SEARCH,
}

/** Localized tab title. Read inside composition so a language switch recomposes. */
@Composable
private fun tabLabel(tab: MainTab): String = when (tab) {
    MainTab.HOME -> LocalStrings.current.tabHome
    MainTab.ALBUM -> LocalStrings.current.tabAlbum
    MainTab.FILES -> LocalStrings.current.tabFiles
    MainTab.SEARCH -> LocalStrings.current.tabSearch
}

@Composable
private fun tabIcon(tab: MainTab) = when (tab) {
    MainTab.HOME -> Icons.Default.Home
    MainTab.ALBUM -> Icons.Default.PhotoLibrary
    MainTab.FILES -> Icons.Default.Folder
    MainTab.SEARCH -> Icons.Default.Search
}

/**
 * Reference-stable `() -> Int` provider for the current tab ordinal.
 * See the call site: LiquidBottomTabs must observe the ordinal *change*
 * through its flows instead of having its state reset by recomposition.
 */
@Composable
private fun rememberSelectedIndexProvider(ordinal: Int): () -> Int {
    val latest by rememberUpdatedState(ordinal)
    return remember { { latest } }
}

/**
 * Vertical room every tab screen must keep clear for the floating bottom
 * bar (glass lens or plain bar): bar height plus its 12 dp margins and the
 * safe-drawing inset allowance. Used both for scroll content padding and
 * for lifting snackbars above the bar - an unpadded snackbar hosts behind
 * the translucent glass and reads as a second, stacked navigation bar.
 */
val BottomBarClearance = 112.dp

/**
 * Insets a bar docked to the bottom edge may claim: horizontal (display
 * cutout in landscape, gesture rails) plus the navigation bar. Never the top
 * one - `safeDrawing` also carries the status-bar top inset, and a
 * bottom-docked bar can never be occluded from above, so claiming it padded
 * dead space into the capsule / action bar.
 */
private val BottomBarInsetsSides = WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom

/**
 * App shell: NavigationSuiteScaffold renders a navigation rail on wide
 * windows; on compact widths the stock bar is replaced by the
 * LiquidBottomTabs glass bar from Kyant0/AndroidLiquidGlass (ported under
 * ui.glass) overlaying the content.
 *
 * CRITICAL: layerBackdrop must record a SIBLING of the glass element - never
 * an ancestor that contains it. Recording a subtree that includes the glass
 * itself creates a RenderNode cycle; hwui then recurses prepareTree until the
 * RenderThread stack overflows and the app dies on launch (seen on the
 * Android 16 16KB emulator with backdrop 2.0.0-alpha03).
 */
@Composable
fun MainShell(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    wallpaperBitmap: ImageBitmap?,
    glassBarEnabled: Boolean = true,
    /**
     * False while a tab owns the bottom strip itself - the album tab inside a
     * collection swaps the tab bar for its own action bar, mirroring how a
     * phone gallery hands the bottom over to the folder it opened. Two bars
     * stacked at the same edge is the bug this prevents.
     */
    showTabBar: Boolean = true,
    content: @Composable () -> Unit,
) {
    val layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(
        currentWindowAdaptiveInfo(),
    )
    val compact = layoutType == NavigationSuiteType.NavigationBar ||
        layoutType == NavigationSuiteType.ShortNavigationBarCompact

    if (compact) {
        // Hoisted for the backdrop draw block below (draw lambdas cannot read
        // composable state directly). Drawing the theme background first avoids
        // transparent pixels in the bar when no wallpaper is set (official
        // Glass Bottom Bar tutorial, step 2).
        val backdropBackground = MaterialTheme.colorScheme.background
        val backdrop = rememberLayerBackdrop {
            drawRect(backdropBackground)
            drawContent()
        }
        Box(Modifier.fillMaxSize()) {
            // Backdrop recording covers ONLY the content behind the bar and
            // is a sibling of the bar (see above: recording an ancestor that
            // contains the glass itself overflows hwui's RenderThread).
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) {
                WallpaperLayer(wallpaperBitmap)
                // Full-bleed: content flows BEHIND the floating glass bar so
                // the backdrop has something to refract. Each tab screen
                // reserves its own bottom clearance (bar height + margins +
                // safe insets) inside its scroll container.
                Box(Modifier.fillMaxSize()) { content() }
            }
            if (!showTabBar) {
                // The screen owns the bottom edge; nothing to draw here.
            } else if (glassBarEnabled) LiquidBottomTabs(
                // Stable provider lambda: LiquidBottomTabs keys its internal
                // currentIndex state on this lambda instance. A fresh
                // `{ selected.ordinal }` every recomposition would reset that
                // state to the new index before its snapshotFlows observe the
                // change, swallowing tap-driven transitions so the lens never
                // slides (only long-press drags, which drive the animation
                // directly, appeared to work).
                selectedTabIndex = rememberSelectedIndexProvider(selected.ordinal),
                onTabSelected = { index -> onSelect(MainTab.entries[index]) },
                backdrop = backdrop,
                tabsCount = MainTab.entries.size,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Bottom + horizontal only: safeDrawing also carries the
                    // status-bar top inset, which a bottom-docked bar must not
                    // claim - it padded dead space into the capsule.
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(BottomBarInsetsSides))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                MainTab.entries.forEach { tab ->
                    val tabSelected = tab == selected
                    // Neutral ink instead of the theme accent: the glass bar
                    // stays transparent and decoupled from the chosen color.
                    val tint = if (tabSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    LiquidBottomTab(onClick = { onSelect(tab) }) {
                        val label = tabLabel(tab)
                        TabIcon(
                            tab,
                            modifier = Modifier.size(28.dp),
                            tint = tint,
                        )
                        Text(
                            label,
                            color = tint,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            } else {
                // Plain Material bar: the settings toggle swaps the lens effect
                // for this cheaper, fully opaque navigation.
                //
                // No windowInsetsPadding here on purpose: NavigationBar already
                // claims NavigationBarDefaults.windowInsets (system bars,
                // horizontal + bottom). Adding safeDrawing on top duplicated the
                // bottom inset and, worse, pulled in the status-bar top inset.
                NavigationBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                ) {
                    MainTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == selected,
                            onClick = { onSelect(tab) },
                            icon = { TabIcon(tab) },
                            label = { Text(tabLabel(tab)) },
                        )
                    }
                }
            }
        }
    } else if (!showTabBar) {
        Box(Modifier.fillMaxSize()) {
            WallpaperLayer(wallpaperBitmap)
            content()
        }
    } else {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                MainTab.entries.forEach { tab ->
                    item(
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        icon = { TabIcon(tab) },
                        label = { Text(tabLabel(tab)) },
                    )
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                WallpaperLayer(wallpaperBitmap)
                content()
            }
        }
    }
}

@Composable
private fun TabIcon(
    tab: MainTab,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    Icon(tabIcon(tab), contentDescription = tabLabel(tab), tint = tint, modifier = modifier)
}
