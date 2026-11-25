package com.linan.barezen_drive.platform

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.linan.barezen_drive.data.evidence.EvidenceController
import com.linan.barezen_drive.data.evidence.EvidenceMedia
import com.linan.barezen_drive.data.evidence.EvidenceMode
import com.linan.barezen_drive.data.evidence.EvidencePlaybackRequest
import com.linan.barezen_drive.evidence.WebEvidenceController
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLAudioElement
import org.w3c.dom.HTMLMediaElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.js

private val webController = WebEvidenceController()

private object WebEvidencePlatform : EvidencePlatform {
    override val controller: EvidenceController = webController

    @Composable
    override fun SecureContent(content: @Composable () -> Unit) = content()

    @Composable
    override fun AutoLock(onLock: () -> Unit) {
        val scope = rememberCoroutineScope()
        DisposableEffect(scope) {
            var timer = 0
            val listener: (org.w3c.dom.events.Event) -> Unit = {
                window.clearTimeout(timer)
                if (documentHidden()) {
                    timer = window.setTimeout({
                        scope.launch { onLock() }
                        window
                    }, 30_000)
                }
            }
            document.addEventListener("visibilitychange", listener)
            onDispose {
                window.clearTimeout(timer)
                document.removeEventListener("visibilitychange", listener)
            }
        }
    }

    @Composable
    override fun rememberRecordingPermissionRequest(onResult: (Boolean) -> Unit): (EvidenceMode) -> Unit = remember { {} }

    @Composable
    override fun Player(request: EvidencePlaybackRequest, onClose: () -> Unit) {
        val media = remember(request.token) {
            if (request.media == EvidenceMedia.VIDEO) document.createElement("video").unsafeCast<HTMLVideoElement>()
            else document.createElement("audio").unsafeCast<HTMLAudioElement>()
        }
        DisposableEffect(media, request.path) {
            media.src = request.path
            media.controls = true
            media.preload = "auto"
            media.setAttribute("controlsList", "nodownload")
            media.style.setProperty("position", "fixed")
            media.style.setProperty("left", "0")
            media.style.setProperty("top", "64px")
            media.style.setProperty("width", "100vw")
            media.style.setProperty("height", "calc(100vh - 64px)")
            media.style.setProperty("background", "#000")
            media.style.setProperty("z-index", "2147483646")
            if (media is HTMLVideoElement) media.setAttribute("playsinline", "true")
            document.body?.appendChild(media)
            onDispose {
                media.pause()
                media.removeAttribute("src")
                media.load()
                media.parentNode?.removeChild(media)
            }
        }
    }
}

@Composable
actual fun evidencePlatform(): EvidencePlatform? = WebEvidencePlatform

private fun documentHidden(): Boolean = js("document.visibilityState === 'hidden'")
private fun dateNow(): Double = js("Date.now()")

actual fun evidenceNowMs(): Long = dateNow().toLong()
