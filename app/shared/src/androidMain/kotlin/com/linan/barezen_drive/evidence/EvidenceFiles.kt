package com.linan.barezen_drive.evidence

import com.linan.barezen_drive.AndroidContext
import com.linan.barezen_drive.data.evidence.EvidenceMode
import com.linan.barezen_drive.data.evidence.SESSION_ID
import java.io.File
import java.util.Locale

object EvidenceFiles {
    fun sessionDir(root: File, vaultId: String, sessionId: String): File {
        require(EvidenceNames.isVaultId(vaultId))
        require(SESSION_ID.matches(sessionId))
        val session = File(File(root, vaultId), sessionId)
        require(session.canonicalPath.startsWith(root.canonicalPath + File.separator))
        return session
    }

    fun recordingsDir(): File = File(AndroidContext.app.filesDir, "recordings")
    fun sessionDir(vaultId: String, sessionId: String): File = sessionDir(recordingsDir(), vaultId, sessionId)
    fun tempMedia(session: File, number: Int, mode: EvidenceMode): File {
        val suffix = if (mode == EvidenceMode.VIDEO) "video.mp4.recording.tmp" else "audio.m4a.recording.tmp"
        return File(session, "%03d_%s".format(Locale.US, number, suffix))
    }
    fun cipherFile(session: File, number: Int, mode: EvidenceMode): File =
        File(session, EvidenceNames.segmentName(number, mode))
    fun playbackDir(): File = File(AndroidContext.app.cacheDir, "evidence-playback")
    fun playbackFile(token: String, extension: String): File {
        require(token.matches(Regex("[0-9a-f]{32}")))
        require(extension == "mp4" || extension == "m4a")
        return File(playbackDir(), "$token.$extension")
    }
    fun cipherTemp(session: File, number: Int, mode: EvidenceMode): File =
        File(session, EvidenceNames.segmentName(number, mode) + ".part")
}
