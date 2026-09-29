package com.linan.barezen_drive

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.auth.AuthService
import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The install wizard seeds the owner account from BOOTSTRAP_ADMIN_USER /
 * BOOTSTRAP_ADMIN_PASSWORD on the first boot, and registration is closed until
 * an account exists - so if that seed fails, the instance is unreachable: no
 * account, no way to make one, and the operator looking at a healthy-looking
 * server.
 *
 * The failure used to be swallowed twice (runCatching inside the service, and
 * another one around the call) and never logged. The seed now reports what
 * happened, so the caller can log the reason instead of losing it.
 */
class BootstrapAdminFailureTest {
    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef",
        Files.createTempDirectory("bz-bootfail").toString(), 1L shl 30,
    )

    @Test
    fun aRejectedSeedIsReportedWithItsReason() = testApplication {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.get("/health")

        // Username too short: register() refuses it, and that refusal must
        // surface instead of vanishing.
        val result = AuthService.bootstrapAdmin("ab", "password123")
        assertTrue(result.isFailure, "a seed that did not happen must not look like success")
        assertTrue(result.exceptionOrNull() is ApiException, "the original reason is kept: ${result.exceptionOrNull()}")
    }

    @Test
    fun anUnsetPairIsNotAFailure() = testApplication {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.get("/health")
        // Nothing to do is not an error - most installs never set the pair.
        assertNull(AuthService.bootstrapAdmin(null, null).getOrThrow())
        assertNull(AuthService.bootstrapAdmin("", "").getOrThrow())
    }

    @Test
    fun aSuccessfulSeedStillReportsTheAccount() = testApplication {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.get("/health")
        val seeded = assertNotNull(AuthService.bootstrapAdmin("wizard", "password123").getOrThrow())
        assertEquals("wizard", seeded.username)
        // The seeded account is the owner: it is the one that can administer.
        assertEquals("wizard", AuthService.login("wizard", "password123").user.username)
        // A second attempt has nothing to do - the instance is not fresh.
        assertNull(AuthService.bootstrapAdmin("other", "password123").getOrThrow())
    }
}
