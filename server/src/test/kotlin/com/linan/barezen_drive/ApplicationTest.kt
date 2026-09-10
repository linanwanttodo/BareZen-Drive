package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class ApplicationTest {
    private fun testConfig() = AppConfig(
        port = 0,
        jdbcUrl = "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        dbUser = "sa", dbPassword = "",
        jwtSecret = "test-secret-0123456789abcdef0123456789abcdef",
        storageDir = java.nio.file.Files.createTempDirectory("bz-test").toString(),
        maxFileSize = 1L shl 30,
    )

    @Test
    fun healthOk() = testApplication {
        val cfg = testConfig()
        val storage = LocalStorageProvider(java.nio.file.Path.of(cfg.storageDir))
        application { module(cfg, storage) }
        val res = client.get("/health")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("ok"))
    }
}
