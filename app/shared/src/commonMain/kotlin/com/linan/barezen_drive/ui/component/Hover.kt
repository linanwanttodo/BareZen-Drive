package com.linan.barezen_drive.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Reports hover in/out without a desktop-only API: `Enter`/`Exit` are part of
 * the common pointer event stream, so this behaves the same on Android, desktop
 * and web.
 *
 * Used to reveal row affordances (the select checkbox) that a mouse user would
 * otherwise never learn about - on desktop there is no long-press, so anything
 * reachable only by long-press is unreachable in practice.
 */
fun Modifier.onHoverChanged(onHover: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            when (awaitPointerEvent().type) {
                PointerEventType.Enter -> onHover(true)
                PointerEventType.Exit -> onHover(false)
                else -> Unit
            }
        }
    }
}
