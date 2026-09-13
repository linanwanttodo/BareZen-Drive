package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.BuildInfo
import com.linan.barezen_drive.core.dto.VersionInfoResponse
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class VersionTest {
    private val storageDir = Files.createTempDirectory("bz-version").toString()
    private val json = Json { ignoreUnknownKeys = true }

    // A non-GitHub repo URL keeps the release lookup off the network: the
    // service cannot map it to an API endpoint, and there is no manifest URL,
    // so it reports "unknown".
    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
        updateRepoUrl = "https://example.invalid/not-github",
        updateManifestUrl = null,
    )

    @Test
    fun versionEndpointIsPublicAndReportsServerBuild() = testApplication {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        val res = client.get("/api/version")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val dto = json.decodeFromString<VersionInfoResponse>(res.bodyAsText())
        assertEquals(BuildInfo.NAME, dto.name)
        assertEquals(BuildInfo.VERSION, dto.serverVersion)
        assertEquals(BuildInfo.API_VERSION, dto.apiVersion)
        // Unresolvable release: unknown latest, no assets, never a false update.
        assertNull(dto.latestVersion)
        assertFalse(dto.updateAvailable)
        assertTrue(dto.assets.isEmpty())
    }
}
