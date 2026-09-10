package com.linan.barezen_drive.data.update

import com.linan.barezen_drive.core.BuildInfo
import com.linan.barezen_drive.core.dto.UpdateAssetDto
import com.linan.barezen_drive.core.dto.VersionInfoResponse
import com.linan.barezen_drive.data.repo.FilesRepository
import com.linan.barezen_drive.platform.InstallChannel
import com.linan.barezen_drive.platform.installChannel

/** Outcome of an update check, mapped to user-facing text by the UI layer. */
sealed interface UpdateStatus {
    /** Client and the newest known server version agree. */
    data class UpToDate(val version: String) : UpdateStatus

    /**
     * A newer version exists. [downloadUrl] and [sha256] are set when the
     * release manifest listed a package for this platform; [releaseUrl] is the
     * human-facing release page (always a safe fallback).
     */
    data class Available(
        val version: String,
        val releaseUrl: String?,
        /** True when the client needs a reload rather than a new install. */
        val reloadOnly: Boolean,
        val downloadUrl: String? = null,
        val sha256: String? = null,
    ) : UpdateStatus

    /** The check could not be completed (server offline, no release resolved). */
    data object Failed : UpdateStatus
}

/**
 * Cross-channel update check.
 *
 * The server is asked for its build and the newest release it can resolve, so
 * Android, iOS, desktop and web all follow one rule: compare what the server
 * reports with what this build carries. When the release manifest advertised a
 * package for this platform, the result carries a direct download URL; the web
 * client is served by the same server, so for it a version gap means the loaded
 * bundle is stale and a reload is enough.
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
        arch: String = "any",
    ): UpdateStatus {
        val latest = info.latestVersion
        val target = when {
            latest == null -> info.serverVersion
            BuildInfo.isNewer(latest, info.serverVersion) -> latest
            else -> info.serverVersion
        }
        if (!BuildInfo.isNewer(target, clientVersion)) return UpdateStatus.UpToDate(clientVersion)

        // The web bundle always matches the server that serves it, so a lower
        // clientVersion there can only mean a stale loaded page.
        val reloadOnly = channel == InstallChannel.WEB
        val asset = if (reloadOnly) null else selectAsset(info.assets, channel, arch)
        return UpdateStatus.Available(
            version = target,
            releaseUrl = info.releaseUrl,
            reloadOnly = reloadOnly,
            downloadUrl = asset?.url,
            sha256 = asset?.sha256,
        )
    }

    /**
     * Picks the manifest entry for this client: prefer an exact architecture
     * match, then the universal "any" entry. Web has no package (it reloads).
     */
    fun selectAsset(
        assets: List<UpdateAssetDto>,
        channel: InstallChannel,
        arch: String = "any",
    ): UpdateAssetDto? {
        val platform = platformKey(channel) ?: return null
        val forPlatform = assets.filter { it.platform.equals(platform, ignoreCase = true) }
        return forPlatform.firstOrNull { it.arch.equals(arch, ignoreCase = true) }
            ?: forPlatform.firstOrNull { it.arch.equals("any", ignoreCase = true) }
    }

    private fun platformKey(channel: InstallChannel): String? = when (channel) {
        InstallChannel.ANDROID -> "android"
        InstallChannel.IOS -> "ios"
        InstallChannel.DESKTOP -> "desktop"
        InstallChannel.WEB -> null
    }
}
