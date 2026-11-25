package com.linan.barezen_drive.evidence.recording

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoRecordingConfigTest {
    @Test
    fun videoUses1080pThen720pAndAacMono() {
        assertEquals(listOf(1080, 720), VideoRecordingConfig.preferredHeights)
        assertEquals(44_100, VideoRecordingConfig.audioSampleRate)
        assertEquals(1, VideoRecordingConfig.audioChannels)
        assertEquals(128_000, VideoRecordingConfig.audioBitRate)
    }
}
