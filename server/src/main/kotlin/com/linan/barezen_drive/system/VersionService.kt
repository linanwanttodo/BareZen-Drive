package com.linan.barezen_drive.system

import com.linan.barezen_drive.core.BuildInfo
import com.linan.barezen_drive.core.dto.UpdateAssetDto
import com.linan.barezen_drive.core.dto.UpdateDockerDto
import com.linan.barezen_drive.core.dto.UpdateManifestDto
import com.linan.barezen_drive.core.dto.VersionInfoResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Resolves the newest upstream release and answers GET /api/version.
 *
 * Source order:
 *  1. The release manifest (update.json) when a manifest URL is configured - a
 *     stable, unauthenticated file with no rate limit that also carries the
 *     per-platform download links.
 *  2. The GitHub REST "latest release" endpoint as a fallback, derived from the
 *     repository URL, so deployments configured before the manifest keep working.
 *
 * The lookup is done once per server instead of in each client, so a
 * self-hosted instance behind a restrictive network reports "unknown" rather
 * than making every client try and fail. The result is cached for
 * [CACHE_TTL_MS]; any failure degrades to latestVersion = null and is held
 * off for [FAIL_COOLDOWN_MS] so a broken upstream cannot be re-tried on
 * every public request.
 */
object VersionService {

    private const val CACHE_TTL_MS = 10 * 60 * 1000L
    private const val FAIL_COOLDOWN_MS = 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /** Resolved release, whichever source produced it. */
    private data class Release(
        val tag: String,
        val url: String?,
        val assets: List<UpdateAssetDto> = emptyList(),
        val docker: UpdateDockerDto? = null,
    )

    @Volatile private var cached: Release? = null
    @Volatile private var cachedAtMs: Long = 0
    @Volatile private var failedAtMs: Long = 0
    private val cacheLock = Any()

    /** Test seam: forget any cached release. Called from :server tests. */
    @Suppress("UnusedSymbol")
    fun resetCache() {
        synchronized(cacheLock) {
            cached = null
            cachedAtMs = 0
            failedAtMs = 0
        }
    }

    fun snapshot(repoUrl: String, manifestUrl: String?, githubToken: String?): VersionInfoResponse {
        val release = cachedRelease(repoUrl, manifestUrl, githubToken)
        val serverVersion = BuildInfo.normalize(BuildInfo.VERSION)
        val latest = release?.tag?.let(BuildInfo::normalize)
        return VersionInfoResponse(
            name = BuildInfo.NAME,
            serverVersion = serverVersion,
            apiVersion = BuildInfo.API_VERSION,
            latestVersion = latest,
            releaseUrl = release?.url,
            updateAvailable = latest != null && BuildInfo.isNewer(latest, serverVersion),
            assets = release?.assets ?: emptyList(),
        )
    }

    private fun cachedRelease(repoUrl: String, manifestUrl: String?, githubToken: String?): Release? {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            cached?.let { if (now - cachedAtMs < CACHE_TTL_MS) return it }
            // Negative cache: after a failed lookup, do not let every public
            // GET /api/version block on the 6s upstream timeout again.
            if (now - failedAtMs < FAIL_COOLDOWN_MS) return null
        }
        val fetched = (manifestUrl?.let { fetchManifest(it, githubToken) }
            ?: fetchLatestRelease(repoUrl, githubToken))
        if (fetched == null) {
            synchronized(cacheLock) { failedAtMs = now }
            return null
        }
        synchronized(cacheLock) {
            cached = fetched
            cachedAtMs = now
            failedAtMs = 0
        }
        return fetched
    }

    /** Reads the release manifest (update.json). */
    private fun fetchManifest(manifestUrl: String, githubToken: String?): Release? {
        if (manifestUrl.isBlank()) return null
        return runCatching {
            val body = get(manifestUrl, "application/json", githubToken) ?: return@runCatching null
            val manifest = json.decodeFromString<UpdateManifestDto>(body)
            if (manifest.version.isBlank()) return@runCatching null
            Release(
                tag = manifest.version,
                url = manifest.releaseNotesUrl,
                assets = manifest.assets,
                docker = manifest.docker,
            )
        }.getOrNull()
    }

    /** Fallback: the GitHub REST "latest release" endpoint for a repository URL. */
    private fun fetchLatestRelease(repoUrl: String, githubToken: String?): Release? {
        val apiUrl = apiLatestReleaseUrl(repoUrl) ?: return null
        return runCatching {
            val body = get(apiUrl, "application/vnd.github+json", githubToken) ?: return@runCatching null
            val obj = json.parseToJsonElement(body).jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@runCatching null
            val url = obj["html_url"]?.jsonPrimitive?.contentOrNullSafe()
            Release(tag, url)
        }.getOrNull()
    }

    private fun get(url: String, accept: String, githubToken: String?): String? {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(6))
            .header("Accept", accept)
            .header("User-Agent", BuildInfo.NAME)
            .GET()
        if (!githubToken.isNullOrBlank()) builder.header("Authorization", "Bearer $githubToken")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return if (response.statusCode() == 200) response.body() else null
    }

    /** Maps a repository URL such as https://github.com/owner/repo to its API endpoint. */
    private fun apiLatestReleaseUrl(repoUrl: String): String? {
        val trimmed = repoUrl.trim().trimEnd('/').removeSuffix(".git")
        val idx = trimmed.indexOf("github.com/")
        if (idx < 0) return null
        val path = trimmed.substring(idx + "github.com/".length)
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return "https://api.github.com/repos/${parts[0]}/${parts[1]}/releases/latest"
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()
