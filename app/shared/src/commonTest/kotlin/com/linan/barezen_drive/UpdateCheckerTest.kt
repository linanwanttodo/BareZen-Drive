package com.linan.barezen_drive

import com.linan.barezen_drive.core.dto.VersionInfoResponse
import com.linan.barezen_drive.data.update.UpdateChecker
import com.linan.barezen_drive.data.update.UpdateStatus
import com.linan.barezen_drive.platform.InstallChannel
import kotlin.test.*

class UpdateCheckerTest {

    private fun info(
        serverVersion: String,
        latestVersion: String? = null,
        releaseUrl: String? = null,
        updateAvailable: Boolean = false,
    ) = VersionInfoResponse(
        name = "BareZen-Drive",
        serverVersion = serverVersion,
        apiVersion = 1,
        latestVersion = latestVersion,
        releaseUrl = releaseUrl,
        updateAvailable = updateAvailable,
    )

    @Test
    fun upToDateWhenAllVersionsMatch() {
        val status = UpdateChecker.evaluate(info("0.0.1", "0.0.1"), "0.0.1", InstallChannel.ANDROID)
        assertEquals(UpdateStatus.UpToDate("0.0.1"), status)
    }

    @Test
    fun availableWhenServerReportsNewerClient() {
        val status = UpdateChecker.evaluate(info("0.0.2", releaseUrl = "https://x/y"), "0.0.1", InstallChannel.ANDROID)
        assertEquals(UpdateStatus.Available("0.0.2", "https://x/y", reloadOnly = false), status)
    }

    @Test
    fun availableWhenLatestReleaseIsNewer() {
        val status = UpdateChecker.evaluate(
            info("0.0.1", latestVersion = "0.0.2", releaseUrl = "https://x/y"),
            "0.0.1",
            InstallChannel.IOS,
        )
        assertEquals(UpdateStatus.Available("0.0.2", "https://x/y", reloadOnly = false), status)
    }

    @Test
    fun webClientGetsReloadOnly() {
        val status = UpdateChecker.evaluate(info("0.0.2"), "0.0.1", InstallChannel.WEB)
        assertIs<UpdateStatus.Available>(status)
        assertTrue(status.reloadOnly, "web updates by reloading the served bundle")
    }

    @Test
    fun unknownReleaseFallsBackToServerVersion() {
        // No release resolved and the server matches the client: nothing to do.
        assertEquals(
            UpdateStatus.UpToDate("0.0.1"),
            UpdateChecker.evaluate(info("0.0.1"), "0.0.1", InstallChannel.ANDROID),
        )
    }

    @Test
    fun olderServerNeverLooksLikeAnUpdate() {
        val status = UpdateChecker.evaluate(info("0.0.0", "0.0.0"), "0.0.1", InstallChannel.ANDROID)
        assertEquals(UpdateStatus.UpToDate("0.0.1"), status)
    }
}
