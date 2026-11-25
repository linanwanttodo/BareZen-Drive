package com.linan.barezen_drive.evidence.recording

import java.io.File

interface SegmentRecorder {
    suspend fun start(output: File)
    suspend fun stop()
    fun close()
}
