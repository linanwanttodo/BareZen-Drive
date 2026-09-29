package com.linan.barezen_drive

import com.linan.barezen_drive.api.Throttle
import com.linan.barezen_drive.api.TrustedProxyRange
import com.linan.barezen_drive.api.resolveClientIp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit-level pins for the two rate-limit primitives the auth endpoints depend
 * on: which address a request is keyed by, and whether a budget can be overrun
 * by parallel callers.
 *
 * These run without a socket on purpose - the routing behaviour of the same
 * rules is covered by TrustedProxyTest and RateLimitTest.
 */
class ThrottleTest {

    @AfterTest
    fun reset() {
        Throttle.reset()
        Throttle.trustProxy = false
        Throttle.trustedProxyRanges = emptyList()
    }

    private fun ranges(vararg specs: String) = specs.mapNotNull { TrustedProxyRange.parse(it) }

    // ---- trusted proxy ranges ------------------------------------------------

    @Test
    fun cidrBlocksMatchOnlyTheirOwnRange() {
        val r = ranges("10.0.0.0/8").single()
        assertTrue(r.contains("10.1.2.3"))
        assertTrue(r.contains("10.255.255.255"))
        assertFalse(r.contains("11.0.0.1"))
        assertFalse(r.contains("9.255.255.255"))
    }

    @Test
    fun prefixBoundariesAreExact() {
        val r = ranges("203.0.113.0/24").single()
        assertTrue(r.contains("203.0.113.0"))
        assertTrue(r.contains("203.0.113.255"))
        assertFalse(r.contains("203.0.114.0"))
        // A /32 is one address, /0 is everything - both must be honoured
        // instead of silently widening or narrowing the trust boundary.
        assertTrue(ranges("198.51.100.7/32").single().contains("198.51.100.7"))
        assertFalse(ranges("198.51.100.7/32").single().contains("198.51.100.8"))
        assertTrue(ranges("0.0.0.0/0").single().contains("8.8.8.8"))
    }

    @Test
    fun bareAddressIsTreatedAsASingleHost() {
        val r = ranges("172.17.0.1").single()
        assertTrue(r.contains("172.17.0.1"))
        assertFalse(r.contains("172.17.0.2"))
    }

    @Test
    fun ipv6RangesAndLiteralsWork() {
        val r = ranges("2001:db8::/32").single()
        assertTrue(r.contains("2001:db8:1234::1"))
        assertFalse(r.contains("2001:db9::1"))
        // An IPv4 peer can never satisfy an IPv6 range (and vice versa).
        assertFalse(r.contains("10.0.0.1"))
        assertFalse(ranges("10.0.0.0/8").single().contains("2001:db8::1"))
    }

    @Test
    fun malformedRangesAreRejectedInsteadOfTrusted() {
        // A typo must fail closed: parsing it as "trust everything" would put
        // the limiter back where it started.
        assertNull(TrustedProxyRange.parse(""))
        assertNull(TrustedProxyRange.parse("not-an-address"))
        assertNull(TrustedProxyRange.parse("10.0.0.0/33"))
        assertNull(TrustedProxyRange.parse("10.0.0.0/abc"))
        assertNull(TrustedProxyRange.parse("10.0.0.256/8"))
        assertNull(TrustedProxyRange.parse("10.0.0.0/8/8"))
        assertNull(TrustedProxyRange.parse("example.com"))
    }

    // ---- which address a request is keyed by ---------------------------------

    @Test
    fun headerIsIgnoredWhenNoProxyIsConfigured() {
        assertEquals("198.51.100.9", resolveClientIp("198.51.100.9", "1.2.3.4", trustAnyPeer = false, trustedRanges = emptyList()))
    }

    @Test
    fun headerIsIgnoredWhenThePeerIsNotATrustedProxy() {
        // The peer reached the port directly, so whatever it claims about
        // somebody else is its own invention.
        assertEquals(
            "198.51.100.9",
            resolveClientIp("198.51.100.9", "1.2.3.4", trustAnyPeer = true, trustedRanges = ranges("10.0.0.0/8")),
        )
    }

    @Test
    fun configuredProxySpeaksForItsClient() {
        assertEquals(
            "1.2.3.4",
            resolveClientIp("10.0.0.1", "1.2.3.4", trustAnyPeer = false, trustedRanges = ranges("10.0.0.0/8")),
        )
    }

    @Test
    fun aChainedProxyResolvesToTheHopThatEnteredTheChain() {
        // client, then an untrusted edge proxy: everything left of the last
        // non-proxy hop was written by the caller, so the edge proxy's own
        // address is the answer, not the forged prefix.
        assertEquals(
            "203.0.113.7",
            resolveClientIp("10.0.0.1", "1.1.1.1, 203.0.113.7", trustAnyPeer = false, trustedRanges = ranges("10.0.0.0/8")),
        )
        // Two of our own proxies in the chain: the client is the one that is not.
        assertEquals(
            "203.0.113.7",
            resolveClientIp("10.0.0.1", "203.0.113.7, 10.0.0.2", trustAnyPeer = false, trustedRanges = ranges("10.0.0.0/8")),
        )
    }

    @Test
    fun anAllProxyChainFallsBackToTheSocket() {
        assertEquals(
            "10.0.0.1",
            resolveClientIp("10.0.0.1", "10.0.0.2, 10.0.0.3", trustAnyPeer = false, trustedRanges = ranges("10.0.0.0/8")),
        )
    }

    @Test
    fun garbageInTheHeaderNeverBecomesTheKey() {
        val trusted = ranges("10.0.0.0/8")
        assertEquals("203.0.113.7", resolveClientIp("10.0.0.1", "not-an-ip, 203.0.113.7", false, trusted))
        assertEquals("10.0.0.1", resolveClientIp("10.0.0.1", "garbage", false, trusted))
        assertEquals("10.0.0.1", resolveClientIp("10.0.0.1", null, false, trusted))
    }

    @Test
    fun legacyTrustProxyFlagKeepsWorkingWithoutRanges() {
        // TRUST_PROXY=true predates the CIDR list; existing deployments set it
        // and must keep working, so with no range configured every peer is
        // still believed and the right-most entry wins.
        assertEquals("1.2.3.4", resolveClientIp("10.0.0.1", "1.2.3.4", trustAnyPeer = true, trustedRanges = emptyList()))
        assertEquals("1.2.3.4", resolveClientIp("10.0.0.1", "9.9.9.9, 1.2.3.4", trustAnyPeer = true, trustedRanges = emptyList()))
    }

    // ---- budget accounting ---------------------------------------------------

    @Test
    fun tryConsumeDeniesOnceTheBudgetIsSpent() {
        repeat(3) { assertTrue(Throttle.tryConsume("acct:a", max = 3, windowMs = 60_000, now = 1_000)) }
        assertFalse(Throttle.tryConsume("acct:a", max = 3, windowMs = 60_000, now = 1_000))
        // A new window starts clean.
        assertTrue(Throttle.tryConsume("acct:a", max = 3, windowMs = 60_000, now = 61_000))
    }

    @Test
    fun refundGivesTheUnitBackInsideTheWindow() {
        assertTrue(Throttle.tryConsume("acct:b", max = 1, windowMs = 60_000, now = 1_000))
        Throttle.refund("acct:b", 60_000, now = 1_000)
        assertTrue(Throttle.tryConsume("acct:b", max = 1, windowMs = 60_000, now = 1_000))
    }

    @Test
    fun refundDoesNotTouchTheNextWindow() {
        assertTrue(Throttle.tryConsume("acct:c", max = 1, windowMs = 1_000, now = 0))
        // The window rolled over: the old unit is gone, and a refund arriving
        // late must not hand out budget in the new window that nobody spent.
        Throttle.refund("acct:c", 1_000, now = 5_000)
        assertTrue(Throttle.tryConsume("acct:c", max = 1, windowMs = 1_000, now = 5_000))
        assertFalse(Throttle.tryConsume("acct:c", max = 1, windowMs = 1_000, now = 5_000))
    }

    /**
     * The whole point of the atomic primitive: N threads racing for a budget of
     * [max] must be let through exactly [max] times. peek()+record() cannot
     * promise this - every thread reads the same stale count before any of them
     * writes.
     */
    @Test
    fun parallelCallersCannotOverrunTheBudget() {
        val threads = 32
        val max = 20
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val start = CountDownLatch(1)
            val granted = AtomicInteger()
            val futures = (1..threads).map {
                pool.submit {
                    start.await()
                    if (Throttle.tryConsume("acct:race", max = max, windowMs = 60_000, now = 1_000)) {
                        granted.incrementAndGet()
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(max, granted.get(), "exactly the budget may be granted under contention")
        } finally {
            pool.shutdownNow()
        }
    }
}
