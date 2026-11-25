package com.linan.barezen_drive.platform

import androidx.compose.runtime.Composable
import com.linan.barezen_drive.data.evidence.EvidenceController
import com.linan.barezen_drive.data.evidence.EvidenceMode
import com.linan.barezen_drive.data.evidence.EvidencePlaybackRequest

interface EvidencePlatform {
    val controller: EvidenceController

    @Composable
    fun SecureContent(content: @Composable () -> Unit)

    @Composable
    fun AutoLock(onLock: () -> Unit)

    @Composable
    fun rememberRecordingPermissionRequest(onResult: (Boolean) -> Unit): (EvidenceMode) -> Unit

    @Composable
    fun Player(request: EvidencePlaybackRequest, onClose: () -> Unit)
}

@Composable
expect fun evidencePlatform(): EvidencePlatform?

expect fun evidenceNowMs(): Long
