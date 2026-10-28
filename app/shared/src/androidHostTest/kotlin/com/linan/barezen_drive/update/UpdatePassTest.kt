package com.linan.barezen_drive.update

import com.linan.barezen_drive.data.update.UpdateStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ordering rules of the daily pass, pinned as behaviour rather than as
 * code shape: each test is "this callback never ran", which is exactly the
 * property that keeps a release from being lost.
 *
 * The invariant worth restating - a prompt that cannot reach the shade must
 * not be paid for, and a package whose prompt was dropped must not be
 * remembered as staged - is only visible in which lambdas were called, so a
 * plain dispatcher and counters are all this needs; no Context, no device.
 */
class UpdatePassTest {

    private val target = UpdateStatus.Available(
        version = "1.4.0",
        releaseUrl = "https://github.com/linAnhx/BareZen-Drive/releases",
        reloadOnly = false,
        downloadUrl = "https://drive.example/api/update/app.apk",
        fallbackUrl = "https://github.com/up/app.apk",
        sha256 = "abc",
        size = 20_000_000,
    )

    private class Recorder {
        var statusCalls = 0
        var stageCalls = 0
        var notified: MutableList<Pair<String, String>> = mutableListOf()
        var remembered: MutableList<String> = mutableListOf()
    }

    private fun deps(
        rec: Recorder,
        canNotify: Boolean = true,
        status: UpdateStatus = target,
        stagedVersion: String = "",
        staged: String? = "/data/update/app.apk",
        notifyPosted: Boolean = true,
    ) = UpdatePassDeps(
        canNotify = { canNotify },
        checkStatus = { rec.statusCalls++; status },
        currentVersion = "1.3.0",
        stagedVersion = { stagedVersion },
        rememberStaged = { rec.remembered += it },
        stage = { rec.stageCalls++; staged },
        notifyReady = { version, path -> rec.notified += version to path; notifyPosted },
    )

    @Test
    fun withoutADeliverablePromptNothingTouchesTheNetwork() = runTest {
        val rec = Recorder()
        assertEquals(UpdatePassOutcome.DONE, runUpdatePass(deps(rec, canNotify = false)))
        // The whole point of the gate: no version request, no download, no
        // key written, and not even the status consulted.
        assertEquals(0, rec.statusCalls)
        assertEquals(0, rec.stageCalls)
        assertEquals(emptyList(), rec.notified)
        assertEquals(emptyList(), rec.remembered)
    }

    @Test
    fun aDroppedPromptLeavesTheVersionUnremembered() = runTest {
        val rec = Recorder()
        assertEquals(UpdatePassOutcome.DONE, runUpdatePass(deps(rec, notifyPosted = false)))
        assertEquals(1, rec.stageCalls)
        // The prompt was attempted with this exact pair; if the arguments were
        // swapped the tap would carry a version where the APK path belongs.
        assertEquals(listOf("1.4.0" to "/data/update/app.apk"), rec.notified)
        // Re-downloading tomorrow is visible and recoverable; a remembered
        // stage with no prompt would skip this release forever.
        assertEquals(emptyList(), rec.remembered)
    }

    @Test
    fun aPostedPromptRemembersExactlyTheStagedVersion() = runTest {
        val rec = Recorder()
        assertEquals(UpdatePassOutcome.DONE, runUpdatePass(deps(rec)))
        assertEquals(1, rec.stageCalls)
        assertEquals(listOf("1.4.0" to "/data/update/app.apk"), rec.notified)
        assertEquals(listOf("1.4.0"), rec.remembered)
    }

    @Test
    fun aFailedCheckIsRetriedWithoutDownloading() = runTest {
        val rec = Recorder()
        assertEquals(UpdatePassOutcome.RETRY, runUpdatePass(deps(rec, status = UpdateStatus.Failed)))
        assertEquals(1, rec.statusCalls)
        assertEquals(0, rec.stageCalls)
        assertEquals(emptyList(), rec.remembered)
    }

    @Test
    fun aThrowingCheckIsRetriedInsteadOfKillingTheWorker() = runTest {
        val rec = Recorder()
        val boom = UpdatePassDeps(
            canNotify = { true },
            checkStatus = { rec.statusCalls++; throw IllegalStateException("offline") },
            currentVersion = "1.3.0",
            stagedVersion = { "" },
            rememberStaged = { rec.remembered += it },
            stage = { rec.stageCalls++; "/data/update/app.apk" },
            notifyReady = { version, path -> rec.notified += version to path; true },
        )
        assertEquals(UpdatePassOutcome.RETRY, runUpdatePass(boom))
        assertEquals(0, rec.stageCalls)
        assertEquals(emptyList(), rec.notified)
        assertEquals(emptyList(), rec.remembered)
    }

    @Test
    fun aDeadMirrorIsRetriedWithoutNotifying() = runTest {
        val rec = Recorder()
        assertEquals(UpdatePassOutcome.RETRY, runUpdatePass(deps(rec, staged = null)))
        assertEquals(1, rec.stageCalls)
        assertEquals(emptyList(), rec.notified)
        assertEquals(emptyList(), rec.remembered)
    }

    @Test
    fun nothingToUpgradeIsADoneEmptyPass() = runTest {
        val rec = Recorder()
        val upToDate = UpdateStatus.UpToDate("1.3.0")
        assertEquals(UpdatePassOutcome.DONE, runUpdatePass(deps(rec, status = upToDate)))
        assertEquals(0, rec.stageCalls)
        assertEquals(emptyList(), rec.notified)
        assertEquals(emptyList(), rec.remembered)
    }

    @Test
    fun anAlreadyStagedVersionIsNotFetchedAgain() = runTest {
        val rec = Recorder()
        assertEquals(UpdatePassOutcome.DONE, runUpdatePass(deps(rec, stagedVersion = "1.4.0")))
        // The remembered version is what stops the daily re-download.
        assertEquals(0, rec.stageCalls)
        assertEquals(emptyList(), rec.remembered)
    }
}
