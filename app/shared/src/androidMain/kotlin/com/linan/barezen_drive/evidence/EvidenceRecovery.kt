package com.linan.barezen_drive.evidence

import android.content.Context
import com.linan.barezen_drive.AndroidContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object EvidenceRecovery {
    suspend fun recover(context: Context = AndroidContext.app) = withContext(Dispatchers.IO) {
        EvidenceDb.resetUploadingToPending()
        EvidenceDb.interruptActiveSessions(System.currentTimeMillis())
        EvidenceDb.encryptingSegments().forEach { row ->
            File(row.cipherPath).delete()
            File(row.cipherPath + ".part").delete()
            File(row.plainPath).delete()
            EvidenceDb.markSegmentInterrupted(row.key, "process stopped", System.currentTimeMillis())
        }
        EvidenceFiles.playbackDir().listFiles()?.forEach { it.delete() }
    }
}
