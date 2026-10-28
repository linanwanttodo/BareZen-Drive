package com.linan.barezen_drive.update

import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.linan.barezen_drive.AndroidContext
import com.linan.barezen_drive.core.BuildInfo
import com.linan.barezen_drive.data.api.AppClient
import com.linan.barezen_drive.data.local.AppPreferences
import com.linan.barezen_drive.data.local.TokenStorage
import com.linan.barezen_drive.data.update.UpdateChecker
import com.linan.barezen_drive.data.update.UpdateStatus
import com.linan.barezen_drive.data.update.shouldAttemptBackgroundStage
import com.linan.barezen_drive.platform.TransferNotifier
import com.linan.barezen_drive.platform.stageUpdateApk

/**
 * Checks the instance for a new stable release and, when one exists, downloads
 * and verifies it. Stopping short of the install is the whole design: Android
 * owns that confirmation.
 *
 * A signed-out pass has nothing to ask, so it exits success without touching the
 * network - same guard the sync workers use.
 */
class UpdateAutoWorker(context: android.content.Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Cold start by WorkManager still runs Application.onCreate first, but
        // the sync workers re-seed here too and re-seeding is free.
        runCatching { AndroidContext.init(applicationContext) }
        if (TokenStorage.baseUrl.isBlank() || TokenStorage.accessToken == null) return Result.success()
        // The gate that comes before everything: if no prompt could reach the
        // shade (Android 13+ refusal, or the check itself failing - read it as
        // "cannot notify"), this pass must not download a package it cannot
        // announce. Downloading anyway and remembering the version as staged
        // would silently lose that release forever, because shouldAutoStage
        // skips an already-staged version. Fail-closed, and checked before
        // any network access: no reason to spend a request, let alone 20 MB,
        // on a pass that has no way to finish.
        val canNotify = runCatching { TransferNotifier.canNotify() }.getOrDefault(false)
        if (!canNotify) return Result.success()
        val prefs = AppPreferences.get()
        val status = runCatching { UpdateChecker.check(AppClient.files) }.getOrNull() ?: return Result.retry()
        // An unreachable or broken instance is "not this time, maybe next":
        // check() reports Failed instead of throwing, so the retry decision
        // has to be read off the status, not off an exception.
        if (status is UpdateStatus.Failed) return Result.retry()
        val target = shouldAttemptBackgroundStage(
            canNotify = canNotify,
            status = status,
            currentVersion = BuildInfo.VERSION,
            stagedVersion = prefs.stagedUpdateVersion,
        ) ?: return Result.success()

        val staged = stageUpdateApk(updateMirrorUrls(target), target.sha256, target.size)
            ?: return Result.retry()  // a dead mirror is worth another pass
        // The prompt reports whether it actually went out. The key is written
        // only on the strength of that: a stage whose notification was dropped
        // (icon gone, channel disabled, system said no) must leave nothing
        // remembered, or the next pass would skip this release and the hint
        // would be lost silently and permanently. Without the key the next
        // pass re-stages - costly, but visible, which is the whole point.
        if (UpdateNotifier.notifyReady(target.version, staged)) {
            prefs.stagedUpdateVersion = target.version
        }
        return Result.success()
    }

    companion object {
        const val WORK = "barezen-update-auto"
        /** One pass a day is enough: a release is published by hand, not hourly. */
        const val INTERVAL_HOURS = 24L
    }
}

/**
 * Mirrors of the same package, tried in order: the server proxy first, the
 * upstream release link second. Which hop is flaky (the phone's route to
 * GitHub or the server's) is unknown, so both are offered to the downloader.
 * Pure, and pinned by a host test, because the ordering is the whole mirror
 * policy of the background pass.
 */
internal fun updateMirrorUrls(target: UpdateStatus.Available): List<String> =
    listOfNotNull(target.downloadUrl, target.fallbackUrl)
