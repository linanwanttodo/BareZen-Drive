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
     * Whether `clientIp()` may read the forwarding header from ANY peer. Off by
     * default: the header is attacker-controlled unless the process really sits
     * behind a proxy that overwrites it, and a forged value would both dodge the
     * limit and mint unbounded map keys. Set from `TRUST_PROXY` at startup.
     *
     * Prefer [trustedProxyRanges]: this switch cannot tell a proxy from an
     * attacker, so it makes the auth limiter forgeable by anyone who reaches the
     * port. Kept for deployments that set TRUST_PROXY today.
     */
    @Volatile
    var trustProxy: Boolean = false

    /**
     * Address ranges allowed to speak for their clients in `X-Forwarded-For`,
     * from `TRUST_PROXY_CIDRS`. Empty (the default) trusts nobody, so a forged
     * header changes nothing even when [trustProxy] is on.
     */
    @Volatile
    var trustedProxyRanges: List<TrustedProxyRange> = emptyList()

    /** Fixed-window counter. Returns true while [key] stays under [max] hits in
     *  the current [windowMs] window; false (deny) once the limit is exceeded.
     *  Same rule as [tryConsume] - this is the name to use for a plain request
     *  rate limit, where the hit IS the thing being counted. */
    fun allow(key: String, max: Int, windowMs: Long, now: Long = System.currentTimeMillis()): Boolean =
        tryConsume(key, max, windowMs, now)

    /** True the first time [key] is seen inside [windowMs]; false for repeats.
     *  Used so one visitor refreshing a share page does not hammer the DB. */
    fun firstSince(key: String, windowMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        pruneIfNeeded(now)
        val prev = lastSeen[key]
        if (prev != null && now - prev < windowMs) return false
        lastSeen[key] = now
        return true
    }

    /** Hits recorded for [key] in the current window, without consuming one.
     *  Diagnostics only - reading and then writing back is not atomic, so never
     *  gate a request on this (see [tryConsume]). */
    fun peek(key: String, windowMs: Long, now: Long = System.currentTimeMillis()): Int {
        val bucket = buckets[key] ?: return 0
        return synchronized(bucket) {
            if (now - bucket.windowStart >= windowMs) 0 else bucket.count
        }
    }

    /** One recorded failure for [key]. Same caveat as [peek]: the caller cannot
     *  have made the check-and-record pair atomic. */
    fun record(key: String, windowMs: Long, now: Long = System.currentTimeMillis()) {
        allow(key, Int.MAX_VALUE, windowMs, now)
    }

    /**
     * Claims one unit of [key]'s budget and reports whether the claim was
     * inside it: true while fewer than [max] units are claimed in the current
     * window, false once the budget is spent. The claim and the count happen
     * under one lock, so N concurrent callers can never push the window past
     * [max] - the reason this exists instead of peek()+record(), which lets a
     * burst of parallel requests all pass a check they read before any of them
     * wrote.
     *
     * Units are claimed by callers that are about to *attempt* something, so a
     * success has to hand its unit back through [refund] - otherwise a busy
     * shared account would lock itself out on honest logins.
     */
    fun tryConsume(key: String, max: Int, windowMs: Long, now: Long = System.currentTimeMillis()): Boolean {
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

    /**
     * Gives a [tryConsume] unit back after the attempt turned out not to count
     * (a successful login, say). Ignored when the window has already rolled
     * over: subtracting from the new window would free budget nobody spent.
     */
    fun refund(key: String, windowMs: Long, now: Long = System.currentTimeMillis()) {
        val bucket = buckets[key] ?: return
        synchronized(bucket) {
            if (now - bucket.windowStart < windowMs && bucket.count > 0) bucket.count -= 1
        }
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
private fun String.isIpLiteral(): Boolean = parseIpLiteral(this) != null

/**
 * Parses an address literal into its bytes, or null when it is not one.
 *
 * Deliberately hand-rolled for the IPv4 shape: `InetAddress.getByName` treats a
 * dotted quad it cannot parse as a host name and would send it to the resolver,
 * and this value comes straight from a request header. The IPv6 branch is safe
 * to delegate (a colon means the JDK only ever parses it as a literal).
 */
private fun parseIpLiteral(value: String): ByteArray? {
    val v = value.trim().removePrefix("[").removeSuffix("]")
    if (v.isEmpty() || v.length > 45) return null
    if (':' in v) return runCatching { java.net.InetAddress.getByName(v).address }.getOrNull()
    val parts = v.split('.')
    if (parts.size != 4) return null
    val out = ByteArray(4)
    for (i in 0 until 4) {
        val part = parts[i]
        val n = part.toIntOrNull() ?: return null
        if (part.isEmpty() || part.length > 3 || n !in 0..255) return null
        out[i] = n.toByte()
    }
    return out
}

/**
 * One entry of `TRUST_PROXY_CIDRS`: a CIDR block or a bare address, matched by
 * prefix over the parsed bytes so IPv4 and IPv6 both work and no name lookup
 * ever happens. Unparseable entries are dropped by the caller with a log line -
 * failing closed is the only safe way to reject a typo, because the alternative
 * is trusting every peer again.
 */
internal class TrustedProxyRange private constructor(
    private val network: ByteArray,
    private val prefixBits: Int,
    private val addressBytes: Int,
) {
    fun contains(host: String): Boolean {
        val addr = parseIpLiteral(host) ?: return false
        if (addr.size != addressBytes) return false
        val whole = prefixBits / 8
        for (i in 0 until whole) if (addr[i] != network[i]) return false
        val rest = prefixBits % 8
        if (rest == 0) return true
        val mask = (0xff shl (8 - rest)) and 0xff
        return (addr[whole].toInt() and mask) == (network[whole].toInt() and mask)
    }

    companion object {
        /** null when [spec] is neither `address` nor `address/prefix`. */
        fun parse(spec: String): TrustedProxyRange? {
            val s = spec.trim().removePrefix("[").removeSuffix("]")
            if (s.isEmpty()) return null
            val slash = s.indexOf('/')
            val addr = parseIpLiteral(if (slash < 0) s else s.substring(0, slash)) ?: return null
            val bits = addr.size * 8
            val prefix = if (slash < 0) bits else s.substring(slash + 1).trim().toIntOrNull() ?: return null
            if (prefix !in 0..bits) return null
            return TrustedProxyRange(addr.copyOf(), prefix, addr.size)
        }
    }
}

/**
 * Origin used as the rate-limit key.
 *
 * The socket peer is the only value a client cannot forge, so it is the default
 * and stays the answer whenever the peer is not a proxy this instance was told
 * to believe. `X-Forwarded-For` is read only then, and even then the right-most
 * entry that is NOT itself a configured proxy is the client: everything to its
 * left was written by the caller (or by whoever the caller forwarded for), so
 * a chain of trusted proxies still resolves to the address that entered the
 * chain, and a single forged hop cannot name a third party.
 */
fun ApplicationCall.clientIp(): String = resolveClientIp(
    // remoteAddress, not remoteHost: the latter is a reverse-DNS name, so the
    // key would depend on a PTR lookup (a round trip on the hot path) and on
    // something a client can influence by owning a domain. remoteAddress is the
    // socket's literal peer.
    socketHost = normalizePeerHost(runCatching { request.origin.remoteAddress }.getOrDefault("")),
    forwardedFor = request.headers["X-Forwarded-For"],
    trustAnyPeer = Throttle.trustProxy,
    trustedRanges = Throttle.trustedProxyRanges,
)

/**
 * The socket peer as an address literal.
 *
 * Some engines (the test host among them) report the local host by name
 * ("localhost") rather than by address; that name IS the loopback interface, so
 * it is mapped back to 127.0.0.1 before anything is compared against a range.
 * Nothing else is rewritten - an unrecognised name simply matches no range,
 * which is the fail-closed answer.
 */
internal fun normalizePeerHost(host: String): String {
    val h = host.trim().trimEnd('.').lowercase()
    return when (h) {
        "localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback" -> "127.0.0.1"
        else -> h
    }
}

/** The decision behind [clientIp], split out so it can be tested without a
 *  socket. [trustAnyPeer] is the legacy TRUST_PROXY switch and only applies
 *  while no explicit range is configured; once ranges exist they are the whole
 *  trust boundary. */
internal fun resolveClientIp(
    socketHost: String,
    forwardedFor: String?,
    trustAnyPeer: Boolean,
    trustedRanges: List<TrustedProxyRange>,
): String {
    val socket = normalizePeerHost(socketHost).ifEmpty { "unknown" }
    if (trustedRanges.isEmpty() && !trustAnyPeer) return socket
    val peerIsProxy = trustedRanges.any { it.contains(socket) } || (trustAnyPeer && trustedRanges.isEmpty())
    if (!peerIsProxy) return socket
    val chain = forwardedFor?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.filter { it.isIpLiteral() }
        ?: return socket
    // Right to left: the first hop that is not one of our own proxies is the
    // address that actually reached the chain.
    return chain.asReversed().firstOrNull { hop -> trustedRanges.none { it.contains(hop) } } ?: socket
}

