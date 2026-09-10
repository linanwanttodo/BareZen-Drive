package com.linan.barezen_drive.data.update

import com.linan.barezen_drive.core.BuildInfo
import com.linan.barezen_drive.core.dto.VersionInfoResponse
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.platform.InstallChannel
import com.linan.barezen_drive.platform.installChannel

/** Outcome of an update check, mapped to user-facing text by the UI layer. */
sealed interface UpdateStatus {
    /** Client and the newest known server version agree. */
    data class UpToDate(val version: String) : UpdateStatus

    /** A newer version exists; [version] is the tag to show. */
    data class Available(
        val version: String,
        val releaseUrl: String?,
        /** True when the client needs a reload rather than a new install. */
        val reloadOnly: Boolean,
    ) : UpdateStatus

    /** The check could not be completed (server offline, no release resolved). */
    data object Failed : UpdateStatus
}

/**
 * Cross-channel update check.
 *
 * The server is asked for its build and the newest release it can resolve, so
 * Android, iOS, desktop and web all follow one rule: compare what the server
 * reports with what this build carries. The web client is served by the same
 * server, so for it a version gap means the loaded bundle is stale and a
 * reload is enough; packaged clients open the release page instead.
 */
object UpdateChecker {

    suspend fun check(repo: FilesRepository): UpdateStatus {
        val info = repo.versionInfo().getOrNull() ?: return UpdateStatus.Failed
        return evaluate(info, BuildInfo.VERSION, installChannel)
    }

    /** Pure decision logic, split out so it is testable without a network. */
    fun evaluate(
        info: VersionInfoResponse,
        clientVersion: String,
        channel: InstallChannel,
    ): UpdateStatus {
        val latest = info.latestVersion
            ?: return if (BuildInfo.isNewer(info.serverVersion, clientVersion)) {
                UpdateStatus.Available(info.serverVersion, info.releaseUrl, channel == InstallChannel.WEB)
            } else {
                UpdateStatus.UpToDate(clientVersion)
            }

        val target = if (BuildInfo.isNewer(latest, info.serverVersion)) latest else info.serverVersion
        return when {
            BuildInfo.isNewer(target, clientVersion) -> {
                // The web bundle always matches the server that serves it, so a
                // lower clientVersion there can only mean a stale loaded page.
                val reloadOnly = channel == InstallChannel.WEB
                UpdateStatus.Available(target, info.releaseUrl, reloadOnly)
            }
            else -> UpdateStatus.UpToDate(clientVersion)
        }
    }
}
