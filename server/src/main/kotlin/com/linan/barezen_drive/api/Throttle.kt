package com.linan.barezen_drive.api

import io.ktor.server.application.*
import io.ktor.server.plugins.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Best-effort, in-process rate limiting and event de-duplication. This is a
 * single-instance self-hosted drive, so a small bounded map is enough: there is
 * no cluster to coordinate counters with and no need for a sliding-window
 * approximation. It intentionally trades exactness for O(1) cheap checks on the
 * hot path.
 */
internal object Throttle {
    private class Bucket(@Volatile var windowStart: Long, @Volatile var count: Int)

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private var opsSincePrune = 0

    /**
     * Whether `clientIp()` may read the forwarding header. Off by default: the
     * header is attacker-controlled unless the process really sits behind a
     * proxy that overwrites it, and a forged value would both dodge the limit
     * and mint unbounded map keys. Set from `TRUST_PROXY` at startup.
     */
    @Volatile
    var trustProxy: Boolean = false

    /** Fixed-window counter. Returns true while [key] stays under [max] hits in
     *  the current [windowMs] window; false (deny) once the limit is exceeded. */
    fun allow(key: String, max: Int, windowMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        pruneIfNeeded(now)
        val bucket = buckets.computeIfAbsent(key) { Bucket(now, 0) }
        return synchronized(bucket) {
            if (now - bucket.windowStart >= windowMs) {
                bucket.windowStart = now
                bucket.count = 1
            } else {
                bucket.count += 1
            }
            bucket.count <= max
        }
    }

    /** True the first time [key] is seen inside [windowMs]; false for repeats.
     *  Used so one visitor refreshing a share page does not hammer the DB. */
    fun firstSince(key: String, windowMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        pruneIfNeeded(now)
        val prev = lastSeen[key]
        if (prev != null && now - prev < windowMs) return false
        lastSeen[key] = now
        return true
    }

    /** Hits recorded for [key] in the current window, without consuming one. */
    fun peek(key: String, windowMs: Long, now: Long = System.currentTimeMillis()): Int {
        val bucket = buckets[key] ?: return 0
        return synchronized(bucket) {
            if (now - bucket.windowStart >= windowMs) 0 else bucket.count
        }
    }

    /** One recorded failure for [key] (the window itself is fixed by the first
     *  hit; the caller checks the budget with [peek] before letting a request
     *  through, so only genuine failures spend it). */
    fun record(key: String, windowMs: Long, now: Long = System.currentTimeMillis()) {
        allow(key, Int.MAX_VALUE, windowMs, now)
    }

    /** Drop keys whose window has fully elapsed so the maps cannot grow without
     *  bound on a long-lived process. Amortised: runs at most every 4096 ops. */
    private fun pruneIfNeeded(now: Long) {
        if (++opsSincePrune < 4096) return
        opsSincePrune = 0
        buckets.entries.removeIf { now - it.value.windowStart > MAX_WINDOW_MS }
        lastSeen.entries.removeIf { now - it.value > MAX_WINDOW_MS }
        // Distinct-key floods must not outlive the time-based prune: drop the
        // stalest windows until both maps are back under the cap.
        if (buckets.size > MAX_KEYS) {
            buckets.entries.sortedBy { it.value.windowStart }
                .take(buckets.size - MAX_KEYS)
                .forEach { buckets.remove(it.key) }
        }
        if (lastSeen.size > MAX_KEYS) {
            lastSeen.entries.sortedBy { it.value }
                .take(lastSeen.size - MAX_KEYS)
                .forEach { lastSeen.remove(it.key) }
        }
    }

    private const val MAX_WINDOW_MS = 3_600_000L
    private const val MAX_KEYS = 50_000

    /** Clear all windows; called on application (re)start. */
    fun reset() {
        buckets.clear()
        lastSeen.clear()
        opsSincePrune = 0
    }
}

/** Strict IP literal check (IPv4 dotted quad or IPv6): a header value that is
 *  not an address may never become a map key. */
private fun String.isIpLiteral(): Boolean {
    val v = removePrefix("[").removeSuffix("]")
    if (v.isEmpty() || v.length > 45) return false
    return if (v.indexOf(':') >= 0) {
        v.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' } && v.count { it == ':' } in 2..7
    } else {
        val parts = v.split('.')
        parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255 }
    }
}

/**
 * Origin used as the rate-limit key. The socket peer is the only value a client
 * cannot forge, so it is the default. Behind a trusted proxy the peer is the
 * proxy and the real client is the entry that proxy appended: the RIGHT-most
 * one in `X-Forwarded-For`, because everything to its left came from the caller
 * itself. With `Throttle.trustProxy` off, forwarding headers are ignored.
 */
fun ApplicationCall.clientIp(): String {
    val socket = runCatching { request.origin.remoteHost }.getOrDefault("unknown")
    if (!Throttle.trustProxy) return socket
    val forwarded = request.headers["X-Forwarded-For"]
        ?.split(',')
        ?.lastOrNull { it.isNotBlank() }
        ?.trim()
        ?.takeIf { it.isIpLiteral() }
    return forwarded ?: socket
}
