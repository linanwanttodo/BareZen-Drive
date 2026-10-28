package com.linan.barezen_drive.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.linan.barezen_drive.AndroidContext
import java.util.concurrent.TimeUnit

/**
 * Registers the daily update pass. Called from `Application.onCreate`, like the
 * backup workers, so a process started by any job (or by the launcher) keeps the
 * schedule in place.
 *
 * `CONNECTED`, not `UNMETERED`: the operator asked for the package to be fetched
 * on any network. It costs about 20 MB once a day, and only when a stable release
 * actually exists - the check itself is a single small JSON.
 */
object UpdateAuto {

    fun apply() {
        val wm = WorkManager.getInstance(AndroidContext.app)
        val request = PeriodicWorkRequestBuilder<UpdateAutoWorker>(
            UpdateAutoWorker.INTERVAL_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        // UPDATE, not KEEP: the interval and constraints live here, and a build
        // that changes them must take effect on the next launch rather than
        // waiting out a schedule registered by the previous one.
        wm.enqueueUniquePeriodicWork(
            UpdateAutoWorker.WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UpdateAutoWorker.WORK)
    }
}
