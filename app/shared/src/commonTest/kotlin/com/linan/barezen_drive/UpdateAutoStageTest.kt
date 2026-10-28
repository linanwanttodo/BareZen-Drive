package com.linan.barezen_drive

import com.linan.barezen_drive.data.update.UpdateStatus
import com.linan.barezen_drive.data.update.shouldAutoStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The decision a background pass makes on its own, so it is tested as a pure
 * function: what may be fetched without the user asking, and what must not.
 */
class UpdateAutoStageTest {

    private fun available(version: String, url: String? = "https://example/app.apk") =
        UpdateStatus.Available(
            version = version,
            releaseUrl = "https://example/release",
            reloadOnly = false,
            downloadUrl = url,
            sha256 = "ab",
            size = 12L,
        )

    @Test
    fun aStableNewerPackageWithAUrlIsStaged() {
        val got = shouldAutoStage(available("0.3.0"), currentVersion = "0.2.1", stagedVersion = null)
        assertEquals("0.3.0", got?.version)
    }

    @Test
    fun aPreReleaseIsNeverStagedInBackground() {
        assertNull(shouldAutoStage(available("0.3.0-beta.2"), "0.2.1", null))
    }

    @Test
    fun aVersionThatIsNotNewerIsNotStaged() {
        assertNull(shouldAutoStage(available("0.2.1"), currentVersion = "0.2.1", stagedVersion = null))
        assertNull(shouldAutoStage(available("0.1.0"), currentVersion = "0.2.1", stagedVersion = null))
    }

    @Test
    fun noPackageForThisPlatformMeansNothingToStage() {
        assertNull(shouldAutoStage(available("0.3.0", url = null), "0.2.1", null))
    }

    @Test
    fun aWebClientReloadsInsteadOfInstalling() {
        val reload = UpdateStatus.Available("0.3.0", "https://example/release", reloadOnly = true)
        assertNull(shouldAutoStage(reload, "0.2.1", null))
    }

    @Test
    fun theSameVersionIsNotFetchedTwice() {
        assertNull(shouldAutoStage(available("0.3.0"), currentVersion = "0.2.1", stagedVersion = "0.3.0"))
    }

    @Test
    fun aFailedCheckStagesNothing() {
        assertNull(shouldAutoStage(UpdateStatus.Failed, "0.2.1", null))
    }
}
