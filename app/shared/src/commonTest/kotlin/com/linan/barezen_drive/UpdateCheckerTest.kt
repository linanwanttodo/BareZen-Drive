package com.linan.barezen_drive

import com.linan.barezen_drive.core.dto.UpdateAssetDto
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
        assets: List<UpdateAssetDto> = emptyList(),
    ) = VersionInfoResponse(
        name = "BareZen-Drive",
        serverVersion = serverVersion,
        apiVersion = 1,
        latestVersion = latestVersion,
        releaseUrl = releaseUrl,
        updateAvailable = updateAvailable,
        assets = assets,
    )

    private fun asset(platform: String, arch: String, url: String) =
        UpdateAssetDto(platform, arch, "bin", url, "ab".repeat(32), 1)

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
        assertNull(status.downloadUrl, "web has no package to download")
    }

    @Test
    fun androidPicksItsManifestAsset() {
        val status = UpdateChecker.evaluate(
            info(
                "0.0.2",
                latestVersion = "0.0.2",
                assets = listOf(
                    asset("web", "any", "https://x/web.zip"),
                    asset("android", "any", "https://x/app.apk"),
                ),
            ),
            "0.0.1",
            InstallChannel.ANDROID,
        )
        assertIs<UpdateStatus.Available>(status)
        assertEquals("https://x/app.apk", status.downloadUrl)
        assertEquals("ab".repeat(32), status.sha256)
    }

    @Test
    fun exactArchPreferredOverUniversal() {
        val picked = UpdateChecker.selectAsset(
            assets = listOf(
                asset("desktop", "any", "https://x/universal.deb"),
                asset("desktop", "arm64", "https://x/arm64.deb"),
            ),
            channel = InstallChannel.DESKTOP,
            arch = "arm64",
        )
        assertEquals("https://x/arm64.deb", picked?.url)
    }

    @Test
    fun universalUsedWhenNoArchMatch() {
        val picked = UpdateChecker.selectAsset(
            assets = listOf(asset("desktop", "any", "https://x/universal.deb")),
            channel = InstallChannel.DESKTOP,
            arch = "arm64",
        )
        assertEquals("https://x/universal.deb", picked?.url)
    }

    @Test
    fun noAssetMeansNoDownloadUrlButReleasePageStillOffered() {
        val status = UpdateChecker.evaluate(
            info("0.0.2", releaseUrl = "https://x/releases"),
            "0.0.1",
            InstallChannel.ANDROID,
        )
        assertIs<UpdateStatus.Available>(status)
        assertNull(status.downloadUrl)
        assertEquals("https://x/releases", status.releaseUrl)
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
