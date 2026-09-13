package com.linan.barezen_drive.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the precedence the status card relies on. The mapping is a pure function
 * precisely so this can run on the host JVM: the Android side only supplies
 * device readings (session, network capabilities, battery intent) and the
 * decision itself stays testable.
 */
class BackupPauseReasonTest {

    /** Nothing is holding the queue back. */
    @Test
    fun satisfiedPolicyReportsNoPause() {
        assertNull(
            backupPauseReason(
                signedIn = true,
                hasUsableNetwork = true,
                needsCharging = false,
                isCharging = false,
                needsBatteryNotLow = false,
                isBatteryLow = false,
            ),
        )
    }

    @Test
    fun runningOnBatteryWhileChargingOnlyIsRequiredPausesForCharging() {
        assertEquals(
            BackupPauseReason.CHARGING,
            backupPauseReason(
                signedIn = true,
                hasUsableNetwork = true,
                needsCharging = true,
                isCharging = false,
                needsBatteryNotLow = true,
                isBatteryLow = false,
            ),
        )
    }

    @Test
    fun chargingSatisfiesTheChargingRule() {
        assertNull(
            backupPauseReason(
                signedIn = true,
                hasUsableNetwork = true,
                needsCharging = true,
                isCharging = true,
                needsBatteryNotLow = true,
                isBatteryLow = false,
            ),
        )
    }

    @Test
    fun lowBatteryPausesEvenWhenChargingRuleIsOff() {
        assertEquals(
            BackupPauseReason.BATTERY_LOW,
            backupPauseReason(
                signedIn = true,
                hasUsableNetwork = true,
                needsCharging = false,
                isCharging = false,
                needsBatteryNotLow = true,
                isBatteryLow = true,
            ),
        )
    }

    @Test
    fun lowBatteryIsIgnoredWhenTheFloorIsNotRequired() {
        assertNull(
            backupPauseReason(
                signedIn = true,
                hasUsableNetwork = true,
                needsCharging = false,
                isCharging = false,
                needsBatteryNotLow = false,
                isBatteryLow = true,
            ),
        )
    }

    // ---- precedence ----

    @Test
    fun signedOutOutranksEverything() {
        // Auto-sync can be switched on before signing in, and every pass then
        // returns early without touching the queue. Reporting "waiting for
        // network" there would be a plausible-looking lie.
        assertEquals(
            BackupPauseReason.SIGNED_OUT,
            backupPauseReason(
                signedIn = false,
                hasUsableNetwork = false,
                needsCharging = true,
                isCharging = false,
                needsBatteryNotLow = true,
                isBatteryLow = true,
            ),
        )
    }

    @Test
    fun signedOutIsReportedEvenWhenEverythingElseIsSatisfied() {
        assertEquals(
            BackupPauseReason.SIGNED_OUT,
            backupPauseReason(
                signedIn = false,
                hasUsableNetwork = true,
                needsCharging = false,
                isCharging = true,
                needsBatteryNotLow = true,
                isBatteryLow = false,
            ),
        )
    }

    @Test
    fun networkOutranksEveryOtherReason() {
        // Offline is what the user can act on first; the charging rule is
        // irrelevant while there is nowhere to upload to.
        assertEquals(
            BackupPauseReason.NETWORK,
            backupPauseReason(
                signedIn = true,
                hasUsableNetwork = false,
                needsCharging = true,
                isCharging = false,
                needsBatteryNotLow = true,
                isBatteryLow = true,
            ),
        )
    }

    @Test
    fun chargingOutranksLowBattery() {
        // Both rules are unsatisfied: the user-visible instruction ("plug it in")
        // is the one that also fixes the second condition.
        assertEquals(
            BackupPauseReason.CHARGING,
            backupPauseReason(
                signedIn = true,
                hasUsableNetwork = true,
                needsCharging = true,
                isCharging = false,
                needsBatteryNotLow = true,
                isBatteryLow = true,
            ),
        )
    }

    @Test
    fun meteredConnectionWithWifiOnlyArrivesAsNoUsableNetwork() {
        // The caller folds "connected but metered" into hasUsableNetwork=false
        // when Wi-Fi-only is set; this pins the contract between the two.
        assertEquals(
            BackupPauseReason.NETWORK,
            backupPauseReason(
                signedIn = true,
                hasUsableNetwork = false,
                needsCharging = false,
                isCharging = true,
                needsBatteryNotLow = true,
                isBatteryLow = false,
            ),
        )
    }

    @Test
    fun everyReasonIsReachable() {
        // Guards against a precedence rule that shadows a whole branch - the
        // exact failure mode that made the card report "waiting for network"
        // when the real cause was a missing session.
        val reachable = BackupPauseReason.entries.filter { it != BackupPauseReason.SIGNED_OUT }
            .map { target ->
                backupPauseReason(
                    signedIn = true,
                    hasUsableNetwork = target != BackupPauseReason.NETWORK,
                    needsCharging = target == BackupPauseReason.CHARGING,
                    isCharging = target != BackupPauseReason.CHARGING,
                    needsBatteryNotLow = target != BackupPauseReason.NETWORK,
                    isBatteryLow = target == BackupPauseReason.BATTERY_LOW,
                )
            }
        assertEquals(listOf(BackupPauseReason.NETWORK, BackupPauseReason.CHARGING, BackupPauseReason.BATTERY_LOW), reachable)
        assertEquals(
            BackupPauseReason.SIGNED_OUT,
            backupPauseReason(
                signedIn = false,
                hasUsableNetwork = true,
                needsCharging = false,
                isCharging = true,
                needsBatteryNotLow = false,
                isBatteryLow = false,
            ),
        )
    }
}
