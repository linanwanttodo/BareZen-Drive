package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.sql.Database
import java.util.UUID
import kotlin.test.*

/**
 * `/health` answers from a constant, so it keeps answering 200 while every query
 * the app tries to run fails. The container's HEALTHCHECK reads `/readyz`, which
 * runs one statement on the real pool - these tests hold both halves of that
 * promise: the probe notices a pool that stopped answering, and the route turns
 * the answer into a status a supervisor can act on.
 */
class ReadinessTest {

    private fun testConfig() = AppConfig(
        port = 0,
        jdbcUrl = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        dbUser = "sa", dbPassword = "",
        jwtSecret = "test-secret-0123456789abcdef0123456789abcdef",
        storageDir = java.nio.file.Files.createTempDirectory("bz-ready").toString(),
        maxFileSize = 1L shl 30,
    )

    private fun isolatedPool(): HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:h2:mem:probe-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        username = "sa"
        password = ""
        maximumPoolSize = 1
        connectionTimeout = 500
    })

    @Test
    fun readyAnswersWhileTheDatabaseAnswers() = testApplication {
        val cfg = testConfig()
        application { module(cfg, LocalStorageProvider(java.nio.file.Path.of(cfg.storageDir))) }
        val res = client.get("/readyz")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(readinessBody(true).second, res.bodyAsText())
        assertTrue(res.bodyAsText().contains("\"db\":\"ok\""))
    }

    @Test
    fun aPoolThatStoppedAnsweringReadsAsDown() {
        val pool = isolatedPool()
        try {
            val db = Database.connect(pool)
            assertTrue(db.probe(), "a live pool must read as ready")
            pool.close()
            assertFalse(db.probe(), "a pool that cannot hand out a connection must read as down")
        } finally {
            runCatching { pool.close() }
        }
    }

    @Test
    fun downIsNotReadyAndSaysSo() {
        val (status, body) = readinessBody(false)
        assertEquals(HttpStatusCode.ServiceUnavailable, status)
        assertEquals("""{"status":"unready","db":"down"}""", body)
    }

    @Test
    fun livenessStaysSeparateFromReadiness() = testApplication {
        val cfg = testConfig()
        application { module(cfg, LocalStorageProvider(java.nio.file.Path.of(cfg.storageDir))) }
        val res = client.get("/health")
        assertEquals(HttpStatusCode.OK, res.status)
        assertFalse(res.bodyAsText().contains("db"), "liveness must not claim anything about the database")
    }
}
