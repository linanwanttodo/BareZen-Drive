package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.nettyEventLoopThreads
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Start-up knobs that used to be frozen in the source: the JDBC pool was five
 * connections with Hikari's default 30 s wait and no leak detection, and Netty
 * sized its own event loops at "parallelism / 2 + 1", which on the 1C
 * deployment target is ONE thread per group - so a single blocking call (a
 * JDBC wait, an image decode) delayed every other request on the box.
 *
 * These are the numbers an operator on a bigger or busier box needs to change,
 * so they are configurable; the defaults stay at the conservative values the
 * 1C/1G target was sized for.
 */
class StartupConfigTest {

    private fun minimalConfig() = AppConfig(
        port = 0,
        jdbcUrl = "jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        dbUser = "sa", dbPassword = "",
        jwtSecret = "test-secret-0123456789abcdef0123456789abcdef",
        storageDir = Files.createTempDirectory("bz-startup").toString(),
        maxFileSize = 1L shl 30,
    )

    @Test
    fun theConservativeDefaultsAreWhatTheSmallBoxWasSizedFor() {
        val c = minimalConfig()
        assertEquals(5, c.dbPoolSize, "pool size default")
        assertEquals(30_000L, c.dbConnectionTimeoutMs, "connection wait default")
        assertEquals(120_000L, c.dbLeakDetectionMs, "leak detection default (0 would disable it)")
        // Registration must not be open out of the box (see RegistrationSettingTest).
        assertEquals(false, c.registrationOpen, "registration default")
        // No proxy is trusted until an operator says which one.
        assertEquals(emptyList(), c.trustedProxyCidrs, "trusted proxy default")
    }

    @Test
    fun thePoolIsBuiltFromTheConfiguredNumbers() {
        val cfg = DatabaseFactory.poolConfig(
            jdbcUrl = "jdbc:h2:mem:pool;MODE=PostgreSQL",
            user = "sa", password = "",
            poolSize = 12, connectionTimeoutMs = 7_000, leakDetectionMs = 30_000,
        )
        assertEquals(12, cfg.maximumPoolSize)
        assertEquals(7_000L, cfg.connectionTimeout)
        assertEquals(30_000L, cfg.leakDetectionThreshold)
        assertEquals(false, cfg.isAutoCommit, "Exposed manages the transaction itself")
    }

    @Test
    fun leakDetectionCanBeTurnedOff() {
        val cfg = DatabaseFactory.poolConfig("jdbc:h2:mem:pool2;MODE=PostgreSQL", "sa", "", 5, 30_000, 0)
        assertEquals(0L, cfg.leakDetectionThreshold)
    }

    /**
     * Nonsense must not reach Hikari as-is: a zero or negative pool size throws
     * at boot, and a sub-2 s leak threshold is rejected with a warning by
     * Hikari on every start.
     */
    @Test
    fun impossiblePoolNumbersAreClamped() {
        val cfg = DatabaseFactory.poolConfig("jdbc:h2:mem:pool3;MODE=PostgreSQL", "sa", "", 0, 10, 500)
        assertEquals(1, cfg.maximumPoolSize)
        assertEquals(250L, cfg.connectionTimeout)
        assertEquals(2_000L, cfg.leakDetectionThreshold)
    }

    @Test
    fun theEventLoopIsNeverUndersized() {
        val threads = nettyEventLoopThreads()
        assertTrue(threads >= 4, "at least four event-loop threads, got $threads")
        assertEquals(
            maxOf(4, Runtime.getRuntime().availableProcessors() * 2),
            threads,
            "otherwise it follows the 2 x cores heuristic, floored at four",
        )
    }
}
