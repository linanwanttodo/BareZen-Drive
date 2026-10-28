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
import com.linan.barezen_drive.platform.stageUpdateApk

/**
 * Checks the instance for a new stable release and, when one exists, downloads
 * and verifies it. Stopping short of the install is the whole design: Android
 * owns that confirmation.
 *
 * A signed-out pass has nothing to ask, so it exits success without touching the
 * network - same guard the sync workers use.
 *
 * All the decisions live in [runUpdatePass]; this class only connects it to the
 * real objects and translates the outcome into WorkManager's vocabulary.
 */
class UpdateAutoWorker(context: android.content.Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Cold start by WorkManager still runs Application.onCreate first, but
        // the sync workers re-seed here too and re-seeding is free.
        runCatching { AndroidContext.init(applicationContext) }
        if (TokenStorage.baseUrl.isBlank() || TokenStorage.accessToken == null) return Result.success()
        val prefs = AppPreferences.get()
        val deps = UpdatePassDeps(
            // The gate that comes before everything, and the reason it is
            // [UpdateNotifier.canPostReady] rather than the bare permission
            // check: a refused permission, notifications switched off app-wide,
            // or this one channel muted all make the post a silent no-op, and
            // any of them leaves a downloaded package nobody is told about.
            canNotify = { UpdateNotifier.canPostReady() },
            checkStatus = { UpdateChecker.check(AppClient.files) },
            currentVersion = BuildInfo.VERSION,
            stagedVersion = { prefs.stagedUpdateVersion },
            rememberStaged = { prefs.stagedUpdateVersion = it },
            stage = { target -> stageUpdateApk(updateMirrorUrls(target), target.sha256, target.size) },
            notifyReady = { version, path -> UpdateNotifier.notifyReady(version, path) },
        )
        return when (runUpdatePass(deps)) {
            UpdatePassOutcome.DONE -> Result.success()
            UpdatePassOutcome.RETRY -> Result.retry()
        }
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
