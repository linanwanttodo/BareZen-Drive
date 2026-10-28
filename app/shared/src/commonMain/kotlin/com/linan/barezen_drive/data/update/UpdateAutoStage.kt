package com.linan.barezen_drive.data.update

import com.linan.barezen_drive.core.BuildInfo

/**
 * Whether a background pass may download the new package on its own.
 *
 * Deliberately narrow: a stable version that is actually newer, that has a
 * package for this platform, and that is not already sitting on disk. Anything
 * else - a beta, a web client that only needs a reload, a manifest that lists no
 * asset for this ABI - must leave the user exactly where they were. The one
 * thing this cannot decide is whether to *install*: Android shows its own
 * confirmation for that, and no background code gets to skip it.
 *
 * [stagedVersion] is what the last successful download left behind, so a daily
 * pass does not re-fetch 20 MB when nobody taps the notification.
 */
fun shouldAutoStage(
    status: UpdateStatus,
    currentVersion: String,
    stagedVersion: String?,
): UpdateStatus.Available? {
    val available = status as? UpdateStatus.Available ?: return null
    if (available.reloadOnly) return null
    if (available.downloadUrl == null) return null
    if (!BuildInfo.isStable(available.version)) return null
    if (!BuildInfo.isNewer(available.version, currentVersion)) return null
    if (stagedVersion == available.version) return null
    return available
}
