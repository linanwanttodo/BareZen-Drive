package com.linan.barezen_drive.update

import com.linan.barezen_drive.data.update.UpdateStatus
import com.linan.barezen_drive.data.update.shouldAttemptBackgroundStage

/** What one scheduled pass decided, in the worker's own vocabulary. */
internal enum class UpdatePassOutcome { DONE, RETRY }

/**
 * Everything a pass touches, as callbacks. Production wires each one to the
 * real object (see [UpdateAutoWorker]); the host test wires them to counters.
 *
 * The pass is split out from the worker for one reason: its ordering rules are
 * the thing that keeps a release from being lost, and they are only visible as
 * *which callback was not called*. No status request when no prompt could
 * land; no download when that request failed; and above all no "staged" key
 * unless the prompt actually posted. Those are cheap to pin with lambdas and
 * impossible to pin behind a Context.
 */
internal class UpdatePassDeps(
    val canNotify: () -> Boolean,
    val checkStatus: suspend () -> UpdateStatus,
    val currentVersion: String,
    val stagedVersion: () -> String,
    val rememberStaged: (String) -> Unit,
    val stage: suspend (UpdateStatus.Available) -> String?,
    val notifyReady: (version: String, path: String) -> Boolean,
)

/**
 * One pass, in the order that matters.
 *
 * The gate comes before the network on purpose: a pass whose prompt could not
 * reach the shade has no way to finish, so asking the instance for its version
 * - let alone pulling 20 MB - only costs. [UpdateStatus.Failed] and a dead
 * mirror are retried; everything else that says "not this time" is a normal
 * pass, because WorkManager's periodic scheduler runs again tomorrow either
 * way and a retry backoff would only make the next attempt later.
 */
internal suspend fun runUpdatePass(deps: UpdatePassDeps): UpdatePassOutcome {
    val canNotify = deps.canNotify()
    if (!canNotify) return UpdatePassOutcome.DONE
    val status = runCatching { deps.checkStatus() }.getOrNull() ?: return UpdatePassOutcome.RETRY
    // An unreachable or broken instance is "not this time, maybe next":
    // check() reports Failed instead of throwing, so the retry decision has to
    // be read off the status, not off an exception.
    if (status is UpdateStatus.Failed) return UpdatePassOutcome.RETRY
    val target = shouldAttemptBackgroundStage(
        canNotify = canNotify,
        status = status,
        currentVersion = deps.currentVersion,
        stagedVersion = deps.stagedVersion(),
    ) ?: return UpdatePassOutcome.DONE

    val staged = deps.stage(target) ?: return UpdatePassOutcome.RETRY  // a dead mirror is worth another pass
    // The prompt reports whether it actually went out, and the key is written
    // only on the strength of that: a stage whose notification was dropped
    // (icon gone, channel disabled, system said no) must leave nothing
    // remembered, or the next pass would skip this release and the hint would
    // be lost silently and permanently. Without the key the next pass
    // re-stages - costly, but visible, which is the whole point.
    if (deps.notifyReady(target.version, staged)) deps.rememberStaged(target.version)
    return UpdatePassOutcome.DONE
}
