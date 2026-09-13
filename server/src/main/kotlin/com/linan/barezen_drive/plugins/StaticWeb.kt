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

        // Path traversal defense. Each segment is decoded on its own and then judged
        // as a whole path element: only a segment that IS "." / ".." or that carries a
        // separator or drive marker after decoding is rejected. Comparing the decoded
        // text with a bare ".." substring used to reject legitimate names such as
        // "chart..final.png", which then silently fell through to the SPA fallback.
        // A segment that fails to decode keeps its raw form for the check, so a
        // literal "%" in a filename stays usable (the raw path is what gets served).
        val unsafe = segments.any { raw ->
            val decoded = runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8) }.getOrDefault(raw)
            decoded == "." || decoded == ".." ||
                decoded.contains('/') || decoded.contains('\\') ||
                decoded.contains(':') || decoded.contains('\u0000')
        }
        val assetPath = if (unsafe || segments.isEmpty()) null else "web/" + segments.joinToString("/")
        val asset = assetPath?.let { path -> loadAsset(path)?.let { bytes -> path to bytes } }

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
                val index = loadAsset("web/index.html")
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

/**
 * Reads a classpath asset, memoising the result.
 *
 * The classpath is fixed for the lifetime of the process, so a cached entry can
 * never go stale. Without the cache every request re-opened the resource and, in
 * the jar layout, re-inflated it; index.html in particular is the answer to every
 * SPA route and was re-read on each one.
 *
 * The cache is bounded twice: an entry larger than [MAX_ENTRY_BYTES] is served but
 * never retained (the wasm payloads are multi-megabyte and the browser caches them
 * via the immutable header anyway), and the retained total stays under
 * [MAX_TOTAL_BYTES] so the process keeps its headroom on a 1 GB host.
 */
private object AssetCache {
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 24L * 1024 * 1024

    /** Access-ordered so iteration yields the least recently used entry first. */
    private val entries = LinkedHashMap<String, ByteArray>(64, 0.75f, true)
    private var cachedBytes = 0L

    @Synchronized
    fun getOrLoad(path: String, loader: () -> ByteArray?): ByteArray? {
        entries[path]?.let { return it }
        val bytes = loader() ?: return null
        if (bytes.size > MAX_ENTRY_BYTES) return bytes
        entries[path] = bytes
        cachedBytes += bytes.size
        evictWhileOverBudget()
        return bytes
    }

    private fun evictWhileOverBudget() {
        val iterator = entries.entries.iterator()
        while (cachedBytes > MAX_TOTAL_BYTES && iterator.hasNext()) {
            cachedBytes -= iterator.next().value.size
            iterator.remove()
        }
    }
}

private fun loadAsset(path: String): ByteArray? =
    AssetCache.getOrLoad(path) {
        // A path that names a classpath directory (a trailing "/" the client never
        // asked for, say) resolves to a URL that cannot be read; treating that as
        // "no asset" lets the SPA fallback answer instead of turning it into a 500.
        runCatching {
            Application::class.java.classLoader.getResource(path)?.readBytes()
        }.getOrNull()
    }
