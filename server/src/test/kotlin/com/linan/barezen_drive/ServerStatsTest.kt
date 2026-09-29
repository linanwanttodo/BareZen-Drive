package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.ServerStatsDto
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class ServerStatsTest {
    private val storageDir = Files.createTempDirectory("bz-stats").toString()
    // A guest account has to exist for the ownership test below, and admitting
    // it is exactly what the registration switch controls.
    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
        registrationOpen = true,
    )
    private var auth = ""
    private var guestAuth = ""
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        val storage = LocalStorageProvider(java.nio.file.Path.of(c.storageDir))
        application { module(c, storage) }
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        val login = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"guest1","password":"password123"}""") }
        val guestLogin = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"guest1","password":"password123"}""") }
        guestAuth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(guestLogin.bodyAsText())!!.groupValues[1]
    }

    @Test
    fun statsRequireAuth() = testApplication {
        setup()
        val res = client.get("/api/server/stats")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    /**
     * The payload is the host's own profile: total CPU/memory/disk, uptime and
     * live network throughput, read straight from /proc. Every other management
     * surface is owner-only, and the registration switch is itself owner-only -
     * so a guest could otherwise self-register its way to a capacity and uptime
     * fingerprint of the machine the drive runs on. Authentication is not
     * enough here; the response has to be the owner's alone.
     */
    @Test
    fun statsAreOwnerOnly() = testApplication {
        setup()
        val guest = client.get("/api/server/stats") { header(HttpHeaders.Authorization, guestAuth) }
        assertEquals(HttpStatusCode.Forbidden, guest.status, guest.bodyAsText())
        assertTrue(!guest.bodyAsText().contains("memTotalBytes"), "no host profile may leak to a guest: ${guest.bodyAsText()}")
        // The owner still gets it.
        val owner = client.get("/api/server/stats") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, owner.status, owner.bodyAsText())
    }

    @Test
    fun statsReturnSaneValues() = testApplication {
        setup()
        val res = client.get("/api/server/stats") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val dto = json.decodeFromString<ServerStatsDto>(res.bodyAsText())
        // Disk is measurable cross-platform from the storage dir.
        assertTrue(dto.diskTotalBytes > 0, "disk total must be positive")
        assertTrue(dto.diskFreeBytes in 0 until dto.diskTotalBytes, "disk free within total")
        // CPU: first call may be -1 (no delta yet), second must be a percentage.
        assertTrue(dto.cpuPercent in -1.0..100.0, "cpu percent range")
        val res2 = client.get("/api/server/stats") { header(HttpHeaders.Authorization, auth) }
        val dto2 = json.decodeFromString<ServerStatsDto>(res2.bodyAsText())
        assertTrue(dto2.cpuPercent in 0.0..100.0, "second poll must yield a real percentage")
        // Memory present on Linux CI/dev machines.
        assertTrue(dto2.memTotalBytes > 0, "mem total from /proc/meminfo")
        assertTrue(dto2.memUsedBytes in 0 until dto2.memTotalBytes)
    }
}
