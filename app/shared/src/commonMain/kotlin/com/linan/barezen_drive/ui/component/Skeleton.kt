package com.linan.barezen_drive.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Loading placeholder shaped like the list it replaces.
 *
 * A centred spinner tells the user "something is happening" but not "what is
 * about to be here" - on a folder list it also collapses the layout for a
 * moment, so the page visibly jumps when the rows land. Skeleton rows keep the
 * geometry, so the transition into content is a fill-in rather than a reflow.
 *
 * One slow sine pulse instead of a sweeping gradient: at 7 rows a shimmer band
 * is mostly off-screen anyway, and a static 40% block reads as "content" more
 * reliably than a fast flash.
 */
@Composable
fun SkeletonList(
    modifier: Modifier = Modifier,
    rows: Int = 7,
    rowHeight: Dp = 64.dp,
    leadingSize: Dp = 40.dp,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(rows) { i ->
            // Vary the name-bar width per row so the block reads as content
            // rather than as a table of identical bars.
            val nameWidthDp = when (i % 3) {
                0 -> 190.dp
                1 -> 300.dp
                else -> 240.dp
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(leadingSize)
                        .alpha(pulse)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            shape,
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Box(
                        Modifier
                            .width(nameWidthDp)
                            .height(14.dp)
                            .alpha(pulse * 0.9f)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                shape,
                            ),
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .width(120.dp)
                            .height(10.dp)
                            .alpha(pulse * 0.7f)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(4.dp),
                            ),
                    )
                }
            }
        }
    }
}

/**
 * Grid-shaped loading placeholder, for surfaces whose content is a photo grid.
 * Same reasoning as [SkeletonList]: a centred spinner leaves the page empty and
 * then the tiles arrive all at once, which reads as a jump rather than a load.
 */
@Composable
fun SkeletonGrid(
    modifier: Modifier = Modifier,
    columns: Int = 4,
    rows: Int = 3,
    spacing: Dp = 4.dp,
) {
    val transition = rememberInfiniteTransition(label = "skeletonGrid")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonGridAlpha",
    )
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        repeat(rows) { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                repeat(columns) { c ->
                    // Vary the pulse per tile so the block does not look like a
                    // single flat sheet.
                    val a = (pulse + ((r * columns + c) % 3) * 0.08f).coerceIn(0.3f, 0.85f)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .alpha(a)
                            .background(MaterialTheme.colorScheme.surfaceVariant, shape),
                    )
                }
            }
        }
    }
}
