package com.linan.barezen_drive

import com.linan.barezen_drive.system.VersionService
import com.linan.barezen_drive.system.nextDailyRefreshAt
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class VersionCacheTest {
    @Test
    fun successfulLookupWaitsUntilTheNextDailyRefresh() {
        fun at(value: String) = Instant.parse(value).toEpochMilli()
        assertEquals(
            at("2026-11-24T03:00:00Z"),
            nextDailyRefreshAt(at("2026-11-24T02:59:59Z"), ZoneOffset.UTC),
        )
        assertEquals(
            at("2026-11-25T03:00:00Z"),
            nextDailyRefreshAt(at("2026-11-24T03:00:00Z"), ZoneOffset.UTC),
        )
        assertEquals(
            at("2026-11-25T03:00:00Z"),
            nextDailyRefreshAt(at("2026-11-24T23:59:00Z"), ZoneOffset.UTC),
        )
    }

    @Test
    fun concurrentVersionRequestsFetchManifestOnlyOnce() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/update.json") { exchange ->
            requests.incrementAndGet()
            Thread.sleep(150)
            val body = """{"name":"test","version":"9.9.9","apiVersion":1,"assets":[]}"""
                .toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val pool = Executors.newFixedThreadPool(8)
        VersionService.resetCache()
        try {
            val start = CountDownLatch(1)
            val results = (1..8).map {
                pool.submit<Any> {
                    start.await()
                    VersionService.snapshot(
                        "https://example.invalid/not-github",
                        "http://127.0.0.1:${server.address.port}/update.json",
                        null,
                    )
                }
            }
            start.countDown()
            results.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, requests.get())
        } finally {
            pool.shutdownNow()
            server.stop(0)
            VersionService.resetCache()
        }
    }
}
