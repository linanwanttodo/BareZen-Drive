package com.linan.barezen_drive.system

import com.linan.barezen_drive.core.dto.ServerStatsDto
import java.io.File

/**
 * System metrics for the home dashboard, read from /proc (the deployment
 * target is a Linux server - Docker containers see host-wide /proc values,
 * which is exactly what "server CPU/memory/network" means here). Disk comes
 * from the platform File API via the configured storage directory.
 *
 * CPU and network rates are deltas against the previous snapshot() call, so
 * they read ~0 when polled rarely and stabilize with regular polling.
 */
object SystemStatsService {
    @Volatile private var lastCpu: CpuSample? = null
    @Volatile private var lastNet: NetSample? = null

    private data class CpuSample(val at: Long, val idle: Long, val total: Long)
    private data class NetSample(val at: Long, val rx: Long, val tx: Long)

    fun snapshot(storageDir: String): ServerStatsDto {
        val now = System.currentTimeMillis()
        val cpuPercent = readCpuPercent(now)
        val (memTotal, memUsed) = readMemory()
        val (rxPerSec, txPerSec) = readNetRates(now)
        val disk = runCatching {
            val f = File(storageDir)
            DiskInfo(total = f.totalSpace, free = f.usableSpace)
        }.getOrDefault(DiskInfo(-1, -1))

        return ServerStatsDto(
            cpuPercent = cpuPercent,
            memTotalBytes = memTotal,
            memUsedBytes = memUsed,
            diskTotalBytes = disk.total,
            diskFreeBytes = disk.free,
            netRxBytesPerSec = rxPerSec,
            netTxBytesPerSec = txPerSec,
            uptimeSeconds = readUptime(),
        )
    }

    private fun readCpuPercent(now: Long): Double {
        val sample = readProcCpu() ?: return -1.0
        val last = lastCpu
        lastCpu = sample
        if (last == null) return -1.0
        val totalDelta = sample.total - last.total
        val idleDelta = sample.idle - last.idle
        if (totalDelta <= 0 || idleDelta < 0) return -1.0
        return ((1.0 - idleDelta.toDouble() / totalDelta) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun readProcCpu(): CpuSample? = runCatching {
        val line = File("/proc/stat").bufferedReader().readLine() ?: return null
        if (!line.startsWith("cpu ")) return null
        val fields = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (fields.isEmpty()) return null
        val idle = (fields.getOrNull(3) ?: 0L) + (fields.getOrNull(4) ?: 0L)
        CpuSample(System.currentTimeMillis(), idle, fields.sum())
    }.getOrNull()

    private fun readMemory(): Pair<Long, Long> = runCatching {
        val values = mutableMapOf<String, Long>()
        File("/proc/meminfo").bufferedReader().forEachLine { line ->
            val parts = line.split(":")
            if (parts.size == 2) {
                values[parts[0].trim()] = parts[1].trim().removeSuffix("kB").trim().toLongOrNull() ?: 0L
            }
        }
        val totalKb = values["MemTotal"] ?: -1L
        val availableKb = values["MemAvailable"] ?: values["MemFree"] ?: -1L
        Pair(totalKb * 1024, (totalKb - availableKb).coerceAtLeast(0) * 1024)
    }.getOrDefault(-1L to -1L)

    private fun readNetRates(now: Long): Pair<Long, Long> = runCatching {
        val counters = readProcNet()
        val last = lastNet
        lastNet = NetSample(now, counters.first, counters.second)
        if (last == null) return Pair(-1L, -1L)
        val elapsed = (now - last.at) / 1000.0
        if (elapsed <= 0) return Pair(-1L, -1L)
        val rx = ((counters.first - last.rx) / elapsed).toLong().coerceAtLeast(0)
        val tx = ((counters.second - last.tx) / elapsed).toLong().coerceAtLeast(0)
        Pair(rx, tx)
    }.getOrDefault(-1L to -1L)

    /** Summed rx/tx bytes over all interfaces. */
    private fun readProcNet(): Pair<Long, Long> {
        var rx = 0L
        var tx = 0L
        File("/proc/net/dev").bufferedReader().forEachLine { line ->
            val parts = line.split(':')
            if (parts.size == 2 && !parts[0].trim().endsWith("lo")) {
                val nums = parts[1].trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
                if (nums.size >= 9) {
                    rx += nums[0]
                    tx += nums[8]
                }
            }
        }
        return Pair(rx, tx)
    }

    private fun readUptime(): Long = runCatching {
        File("/proc/uptime").bufferedReader().readLine()
            ?.split(" ")?.firstOrNull()?.toDoubleOrNull()?.toLong() ?: -1L
    }.getOrDefault(-1L)

    private data class DiskInfo(val total: Long, val free: Long)
}
