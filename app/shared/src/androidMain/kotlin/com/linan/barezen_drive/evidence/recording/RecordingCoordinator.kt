package com.linan.barezen_drive.evidence.recording

import com.linan.barezen_drive.AndroidContext
import com.linan.barezen_drive.i18n.I18n
import com.linan.barezen_drive.data.evidence.EvidenceErrorKind
import com.linan.barezen_drive.data.evidence.EvidenceMode
import com.linan.barezen_drive.data.evidence.EvidenceRecordingState
import com.linan.barezen_drive.evidence.EvidenceDb
import com.linan.barezen_drive.evidence.EvidenceFiles
import com.linan.barezen_drive.evidence.EvidenceSegmentFinalizer
import com.linan.barezen_drive.evidence.EvidenceUploadScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class RecordingCoordinator(
    private val scope: CoroutineScope,
    private val recorder: SegmentRecorder,
    private val vaultId: String,
    private val sessionId: String,
    private val mode: EvidenceMode,
    private val startedAtMs: Long,
    private val dek: ByteArray,
    private val dekVersion: Int,
    private val serverKey: String,
    private val accountId: String,
    private val sessionDir: File,
    private val onFinished: (EvidenceErrorKind?) -> Unit,
) {
    val state = MutableStateFlow(EvidenceRecordingState(mode = mode, sessionId = sessionId, startedAtMs = startedAtMs))
    private val stopRequested = CompletableDeferred<Unit>()
    private val finalizers = mutableListOf<Job>()
    private val stopping = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        job = scope.launch { recordLoop() }
        scope.launch {
            while (isActive) {
                state.update { it.copy(elapsedMs = System.currentTimeMillis() - startedAtMs, uploadLabel = uploadLabel()) }
                delay(1_000)
            }
        }
    }

    fun requestStop() {
        if (stopping.compareAndSet(false, true)) {
            state.update { it.copy(stopping = true) }
            stopRequested.complete(Unit)
        }
    }

    private suspend fun recordLoop() {
        var number = EvidenceDb.nextSegmentNumber(sessionId)
        var failure: EvidenceErrorKind? = null
        try {
            while (scope.isActive && number <= 999) {
                if (sessionDir.usableSpace < MIN_FREE_BYTES) {
                    failure = EvidenceErrorKind.LOW_STORAGE
                    break
                }
                val segmentStarted = System.currentTimeMillis()
                val temp = EvidenceFiles.tempMedia(sessionDir, number, mode)
                try {
                    recorder.start(temp)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    temp.delete()
                    failure = if (mode == EvidenceMode.AUDIO) EvidenceErrorKind.MICROPHONE_UNAVAILABLE else EvidenceErrorKind.CAMERA_UNAVAILABLE
                    break
                }
                withTimeoutOrNull(SEGMENT_MS) { stopRequested.await() }
                val segmentEnded = System.currentTimeMillis()
                try {
                    recorder.stop()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    temp.delete()
                    failure = if (mode == EvidenceMode.AUDIO) EvidenceErrorKind.MICROPHONE_UNAVAILABLE else EvidenceErrorKind.CAMERA_UNAVAILABLE
                    break
                } finally {
                    recorder.close()
                }
                val currentNumber = number
                finalizers += scope.launch(Dispatchers.IO) {
                    val encrypted = EvidenceSegmentFinalizer.finalize(
                        temp, vaultId, sessionId, currentNumber, mode, segmentStarted, segmentEnded,
                        dek, serverKey, accountId, dekVersion,
                    )
                    if (encrypted != null) {
                        state.update { it.copy(sealedSegments = it.sealedSegments + 1, uploadLabel = uploadLabel()) }
                        EvidenceUploadScheduler.enqueue(AndroidContext.app)
                    }
                }
                number += 1
                if (number > 999) failure = EvidenceErrorKind.LOW_STORAGE
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            failure = EvidenceErrorKind.UNKNOWN
        } finally {
            recorder.close()
            finalizers.joinAll()
            EvidenceDb.finishSession(sessionId, "COMPLETED", System.currentTimeMillis())
            dek.fill(0)
            state.value = EvidenceRecordingState(error = failure)
            onFinished(failure)
        }
    }

    private fun uploadLabel(): String? {
        val counts = EvidenceDb.uploadCounts(sessionId)
        if (counts.total == 0) return null
        val status = when {
            counts.failed > 0 -> I18n.strings.evidenceUploadFailed
            counts.active > 0 -> I18n.strings.evidenceUploading
            counts.uploaded == counts.total -> I18n.strings.evidenceAllUploaded
            else -> I18n.strings.evidenceUploading
        }
        return "${counts.uploaded}/${counts.total} · $status"
    }

    private companion object {
        const val SEGMENT_MS = 60_000L
        const val MIN_FREE_BYTES = 32L * 1024 * 1024
    }
}
