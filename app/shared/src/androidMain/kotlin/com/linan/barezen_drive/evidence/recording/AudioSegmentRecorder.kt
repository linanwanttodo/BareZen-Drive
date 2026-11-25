package com.linan.barezen_drive.evidence.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioSegmentRecorder(private val context: Context) : SegmentRecorder {
    private var recorder: MediaRecorder? = null

    override suspend fun start(output: File) {
        output.parentFile?.mkdirs()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        recorder = r
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
            r.setOutputFile(output.absolutePath)
            r.prepare()
            r.start()
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    override suspend fun stop() {
        recorder ?: return
        try { recorder?.stop() } catch (_: IllegalStateException) { }
    }

    override fun close() {
        recorder?.reset()
        recorder?.release()
        recorder = null
    }
}
