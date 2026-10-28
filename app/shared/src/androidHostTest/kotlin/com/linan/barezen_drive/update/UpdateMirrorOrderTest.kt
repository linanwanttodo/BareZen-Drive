package com.linan.barezen_drive.update

import com.linan.barezen_drive.data.update.UpdateStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mirror policy of the daily update pass, pinned as pure logic: the
 * server proxy is always tried first and the upstream release link second
 * (one of the two routes to GitHub is the flaky hop, so both are offered,
 * in that order); a manifest without a fallback still yields the one real
 * source; and a target with no package URLs at all yields nothing for the
 * downloader to try. The worker reads the outcome of stageUpdateApk off
 * this list, so its order is the order the files are fetched in.
 */
class UpdateMirrorOrderTest {

    private fun target(download: String?, fallback: String?) = UpdateStatus.Available(
        version = "1.2.0",
        releaseUrl = "https://github.com/linAnhx/BareZen-Drive/releases",
        reloadOnly = false,
        downloadUrl = download,
        fallbackUrl = fallback,
    )

    @Test
    fun serverProxyIsListedBeforeTheUpstreamMirror() {
        val urls = updateMirrorUrls(
            target("https://drive.example/api/update/app.apk", "https://github.com/up/app.apk"),
        )
        assertEquals(
            listOf("https://drive.example/api/update/app.apk", "https://github.com/up/app.apk"),
            urls,
        )
    }

    @Test
    fun anAbsentFallbackLeavesExactlyTheServerSource() {
        val urls = updateMirrorUrls(target("https://drive.example/api/update/app.apk", null))
        assertEquals(listOf("https://drive.example/api/update/app.apk"), urls)
    }

    @Test
    fun noPackageUrlsYieldNoSourcesToTry() {
        assertEquals(emptyList(), updateMirrorUrls(target(null, null)))
    }
}
