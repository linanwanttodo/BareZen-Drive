package com.linan.barezen_drive.plugins

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.application.Application
import io.ktor.server.response.*
import io.ktor.server.routing.*

private val RESERVED_PREFIXES = listOf("api", "health")

private val CONTENT_TYPES = mapOf(
    "html" to ContentType.Text.Html,
    "js" to ContentType.parse("text/javascript"),
    "css" to ContentType.Text.CSS,
    "wasm" to ContentType.parse("application/wasm"),
    "json" to ContentType.Application.Json,
    "map" to ContentType.Application.Json,
    "png" to ContentType.Image.PNG,
    "ico" to ContentType.parse("image/x-icon"),
    "svg" to ContentType.parse("image/svg+xml"),
    "txt" to ContentType.Text.Plain,
)

// Content-hashed build outputs (webpack emits hash-named wasm/resources) are
// safe to cache forever; entry points whose names stay stable across builds
// must revalidate, or clients keep running a stale client after an upgrade.
private val IMMUTABLE_EXTENSIONS = setOf("wasm", "png", "ico", "svg", "ttf", "woff", "woff2")
private const val CACHE_IMMUTABLE = "public, max-age=31536000, immutable"
private const val CACHE_REVALIDATE = "no-cache"

/**
 * Static hosting of the compiled web client with an SPA fallback to index.html.
 *
 * Serves classpath resources from web/ directly instead of Ktor's staticResources:
 * the plugin enumerates classpath directories at startup and silently matches nothing
 * when the resources live inside a jar (installDist layout), which made every asset
 * request fall through to the SPA fallback. Reading resource bytes per request works
 * uniformly for directory and jar classpaths.
 */
fun Route.staticWeb() {
    get("{...}") {
        val segments = call.request.path().trim('/').split('/').filter { it.isNotEmpty() }

        // Reserved prefixes answer 404 for unmatched subroutes (real /api/** and /health
        // routes are registered earlier and win by specificity).
        if (segments.firstOrNull() in RESERVED_PREFIXES) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }

        // Path traversal defense: only plain segments composed of safe characters may
        // reach the classpath lookup. Decode URL-encoded segments first so %2e%2e is
        // treated the same as "..".
        val decoded = try { java.net.URLDecoder.decode(segments.joinToString("/"), Charsets.UTF_8) } catch (_: Exception) { "" }
        val unsafe = segments.any { it == ".." || it == "." || it.contains('\\') || it.contains(':') }
            || decoded.contains("..") || decoded.contains(':')
        val assetPath = if (unsafe || segments.isEmpty()) null else "web/" + segments.joinToString("/")
        val asset = assetPath?.let { path ->
            Application::class.java.classLoader.getResource(path)?.let { url -> path to url.readBytes() }
        }

        when {
            // Real asset found on the classpath: serve with its content type.
            asset != null -> {
                val ext = asset.first.substringAfterLast('.').lowercase()
                call.response.header(
                    HttpHeaders.CacheControl,
                    if (ext in IMMUTABLE_EXTENSIONS) CACHE_IMMUTABLE else CACHE_REVALIDATE,
                )
                call.respondBytes(asset.second, CONTENT_TYPES[ext] ?: ContentType.Application.OctetStream)
            }
            // SPA fallback: anything that is not a real asset gets the entry page.
            else -> {
                val index = staticWebIndexBytes()
                if (index != null) {
                    call.response.header(HttpHeaders.CacheControl, CACHE_REVALIDATE)
                    call.respondBytes(index, ContentType.Text.Html, HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }
}

private fun staticWebIndexBytes(): ByteArray? =
    Application::class.java.classLoader.getResource("web/index.html")?.readBytes()
