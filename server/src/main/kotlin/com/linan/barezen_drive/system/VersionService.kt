package com.linan.barezen_drive.system

import com.linan.barezen_drive.core.BuildInfo
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
 * The lookup is done here, once per server, instead of in each client:
 * clients only need to reach their own server, the GitHub token (when set)
 * stays server-side, and a self-hosted instance behind a restrictive network
 * simply reports "unknown" rather than making every client try and fail.
 *
 * The result is cached for [CACHE_TTL_MS] so a burst of client polls never
 * turns into a burst of GitHub calls. Any failure (offline, rate limit,
 * malformed response) degrades to latestVersion = null and is not cached, so
 * the next call retries.
 */
object VersionService {

    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    private data class Release(val tag: String, val url: String?)

    @Volatile private var cached: Release? = null
    @Volatile private var cachedAtMs: Long = 0

    /** Test seam: forget any cached release. */
    fun resetCache() {
        cached = null
        cachedAtMs = 0
    }

    fun snapshot(repoUrl: String, githubToken: String?): VersionInfoResponse {
        val release = cachedRelease(repoUrl, githubToken)
        val serverVersion = BuildInfo.normalize(BuildInfo.VERSION)
        val latest = release?.tag?.let(BuildInfo::normalize)
        return VersionInfoResponse(
            name = BuildInfo.NAME,
            serverVersion = serverVersion,
            apiVersion = BuildInfo.API_VERSION,
            latestVersion = latest,
            releaseUrl = release?.url,
            updateAvailable = latest != null && BuildInfo.isNewer(latest, serverVersion),
        )
    }

    private fun cachedRelease(repoUrl: String, githubToken: String?): Release? {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAtMs < CACHE_TTL_MS) return it }
        val fetched = fetchLatestRelease(repoUrl, githubToken) ?: return null
        cached = fetched
        cachedAtMs = now
        return fetched
    }

    private fun fetchLatestRelease(repoUrl: String, githubToken: String?): Release? {
        val apiUrl = apiLatestReleaseUrl(repoUrl) ?: return null
        return runCatching {
            val builder = HttpRequest.newBuilder(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(6))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", BuildInfo.NAME)
                .GET()
            if (!githubToken.isNullOrBlank()) builder.header("Authorization", "Bearer $githubToken")
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return@runCatching null
            val obj = json.parseToJsonElement(response.body()).jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNullSafe() ?: return@runCatching null
            val url = obj["html_url"]?.jsonPrimitive?.contentOrNullSafe()
            Release(tag, url)
        }.getOrNull()
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
