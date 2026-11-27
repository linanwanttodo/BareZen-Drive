package com.linan.barezen_drive.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.js.unsafeCast
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget

private val visualViewport: JsAny? = js("window.visualViewport || null")
private val imeOffset: () -> Int = js("() => window.innerHeight - window.visualViewport.height - window.visualViewport.offsetTop")
private val resizeEvent: () -> Event = js("() => new Event('resize')")

@Composable
actual fun isImeVisible(): Boolean {
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val target = visualViewport?.unsafeCast<EventTarget>()
        if (target == null) {
            onDispose { }
        } else {
            val listener: (Event) -> Unit = { visible = imeOffset() > 120 }
            target.addEventListener("resize", listener)
            listener(resizeEvent())
            onDispose { target.removeEventListener("resize", listener) }
        }
    }
    return visible
}
