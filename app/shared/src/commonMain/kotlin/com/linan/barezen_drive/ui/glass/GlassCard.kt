package com.linan.barezen_drive.ui.glass

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linan.barezen_drive.ui.theme.LocalCardAlpha
import androidx.compose.material3.Text

/**
 * NPatch-style frosted card: a rounded translucent panel that content groups
 * sit on, floating over the wallpaper. Cards keep a readability floor higher
 * than the page panel so text is always legible over a busy background.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val panelAlpha = LocalCardAlpha.current
    // Readability floor: cards carry text, so they stay more opaque than the
    // page-level panel regardless of where the user drags the slider.
    val alpha = (panelAlpha * 1.4f).coerceIn(0.55f, 0.97f)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = alpha),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Column(content = content)
    }
}

/** Section header shown above a card group, NPatch-style. */
@Composable
fun GlassSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}
