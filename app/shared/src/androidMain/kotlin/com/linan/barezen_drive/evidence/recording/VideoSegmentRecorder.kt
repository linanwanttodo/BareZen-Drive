package com.linan.barezen_drive.evidence.recording

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.video.QualitySelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import com.linan.barezen_drive.data.evidence.EvidenceLens
import com.linan.barezen_drive.evidence.service.RecordingLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoSegmentRecorder(
    private val context: Context,
    private val lens: EvidenceLens,
    private val lifecycleOwner: RecordingLifecycleOwner,
    private val executor: ExecutorService,
) : SegmentRecorder {
    private var provider: ProcessCameraProvider? = null
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var finalized = CompletableDeferred<Int>()

    override suspend fun start(output: File) {
        output.parentFile?.mkdirs()
        lifecycleOwner.start()
        val ready = CompletableDeferred<Unit>()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val recorder = Recorder.Builder()
                    .setExecutor(executor)
                    .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD)))
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)
                val selector = if (lens == EvidenceLens.FRONT) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else CameraSelector.DEFAULT_BACK_CAMERA
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, selector, videoCapture)
                capture = videoCapture
                finalized = CompletableDeferred()
                val options = FileOutputOptions.Builder(output).build()
                recording = videoCapture.output.prepareRecording(context, options)
                    .withAudioEnabled()
                    .start(executor) { event ->
                        if (event is VideoRecordEvent.Finalize) finalized.complete(event.error)
                    }
                ready.complete(Unit)
            } catch (t: Throwable) {
                ready.completeExceptionally(t)
            }
        }, Handler(Looper.getMainLooper())::post)
        ready.await()
    }

    override suspend fun stop() {
        val active = recording ?: return
        active.stop()
        val error = withTimeoutOrNull(20_000) { finalized.await() } ?: -1
        if (error != 0) throw IllegalStateException("camera finalize error $error")
    }

    override fun close() {
        recording?.close()
        recording = null
        provider?.let { Handler(Looper.getMainLooper()).post { it.unbindAll() } }
        provider = null
        capture = null
        lifecycleOwner.stop()
    }
}
