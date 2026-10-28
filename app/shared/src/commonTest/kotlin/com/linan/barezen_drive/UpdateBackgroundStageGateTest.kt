package com.linan.barezen_drive

import com.linan.barezen_drive.data.update.UpdateStatus
import com.linan.barezen_drive.data.update.shouldAttemptBackgroundStage
import com.linan.barezen_drive.data.update.shouldAutoStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The "may we even try" gate in front of [shouldAutoStage], tested as the pure
 * function it is. The case table below mirrors, one by one, the seven scenarios
 * UpdateAutoStageTest pins for [shouldAutoStage]; the assertion here is the
 * equivalence itself - with the notification available the gate must change
 * nothing, and without it the pass must not run even on a perfectly stageable
 * release.
 */
class UpdateBackgroundStageGateTest {

    private fun available(version: String, url: String? = "https://example/app.apk") =
        UpdateStatus.Available(
            version = version,
            releaseUrl = "https://example/release",
            reloadOnly = false,
            downloadUrl = url,
            sha256 = "ab",
            size = 12L,
        )

    /** name to (status, currentVersion, stagedVersion), same semantics as UpdateAutoStageTest. */
    private val cases = listOf(
        "stable newer package with a url" to
            Triple(available("0.3.0"), "0.2.1", null),
        "pre-release" to
            Triple(available("0.3.0-beta.2"), "0.2.1", null),
        "not newer (equal)" to
            Triple(available("0.2.1"), "0.2.1", null),
        "not newer (older)" to
            Triple(available("0.1.0"), "0.2.1", null),
        "no package for this platform" to
            Triple(available("0.3.0", url = null), "0.2.1", null),
        "web reload only" to
            Triple(
                UpdateStatus.Available("0.3.0", "https://example/release", reloadOnly = true),
                "0.2.1",
                null,
            ),
        "already staged" to
            Triple(available("0.3.0"), "0.2.1", "0.3.0"),
        "failed check" to
            Triple(UpdateStatus.Failed, "0.2.1", null),
    )

    @Test
    fun anUndeliverablePromptSkipsEvenAStageableRelease() {
        // Positive control first: this exact input would be staged when the
        // prompt can go out, so the null below is the gate and nothing else.
        assertNotNull(shouldAutoStage(available("0.3.0"), "0.2.1", null))
        assertNull(shouldAttemptBackgroundStage(false, available("0.3.0"), "0.2.1", null))
    }

    @Test
    fun aDeliverablePromptKeepsEveryShouldAutoStageVerdict() {
        for ((name, input) in cases) {
            val (status, current, staged) = input
            val gated = shouldAttemptBackgroundStage(true, status, current, staged)
            val plain = shouldAutoStage(status, current, staged)
            assertEquals(plain?.version, gated?.version, name)
            assertEquals(plain == null, gated == null, name)
        }
    }

    @Test
    fun theGateOnlyAddsCanNotifyAndNothingElse() {
        // Every table entry must fall through to null when the prompt is
        // undeliverable, whatever shouldAutoStage would have said.
        for ((name, input) in cases) {
            val (status, current, staged) = input
            assertNull(shouldAttemptBackgroundStage(false, status, current, staged), name)
        }
    }
}
