package com.linan.barezen_drive

import com.linan.barezen_drive.auth.AuthService
import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.*

class BootstrapAdminTest {
    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef",
        Files.createTempDirectory("bz-bootstrap").toString(), 1L shl 30,
        updateRepoUrl = "https://example.invalid/not-github",
    )

    @Test
    fun seedsOwnerOnFreshInstanceOnly() = testApplication {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        // Force the module to run so the database connection exists.
        client.get("/api/version")

        val seeded = AuthService.bootstrapAdmin("wizard", "password123")
        assertNotNull(seeded)
        assertEquals("wizard", seeded.username)

        // A second call must not create anything: the instance is no longer fresh.
        assertNull(AuthService.bootstrapAdmin("other", "password123"))

        // The seeded credentials log in.
        val login = AuthService.login("wizard", "password123")
        assertEquals("wizard", login.user.username)
    }

    @Test
    fun ignoresUnsetPair() {
        assertNull(AuthService.bootstrapAdmin(null, null))
        assertNull(AuthService.bootstrapAdmin("", ""))
    }
}
