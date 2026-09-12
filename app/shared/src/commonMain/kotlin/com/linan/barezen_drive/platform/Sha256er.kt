package com.linan.barezen_drive.platform

expect class Sha256er {
    fun update(bytes: ByteArray)
    fun digestHex(): String
    companion object {
        fun newInstance(): Sha256er
    }
}

/**
 * Pure Kotlin incremental SHA-256 (FIPS 180-4), reused by platform actuals that
 * have no native digest facility (e.g. wasmJs). Handles empty input, arbitrary
 * update() chunk boundaries, and correct length padding in digestHex().
 */
internal class PureSha256 {
    private var state = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
    )
    private val block = ByteArray(64)
    private var blockLen = 0
    private var totalBytes = 0L
    private val w = IntArray(64)

    fun update(bytes: ByteArray) {
        totalBytes += bytes.size
        var i = 0
        while (i < bytes.size) {
            val n = minOf(64 - blockLen, bytes.size - i)
            bytes.copyInto(block, blockLen, i, i + n)
            blockLen += n
            i += n
            if (blockLen == 64) {
                processBlock()
                blockLen = 0
            }
        }
    }

    fun digestHex(): String {
        val bitLen = totalBytes * 8
        block[blockLen++] = 0x80.toByte()
        if (blockLen > 56) {
            while (blockLen < 64) block[blockLen++] = 0
            processBlock()
            blockLen = 0
        }
        while (blockLen < 56) block[blockLen++] = 0
        for (shift in 56 downTo 0 step 8) block[blockLen++] = ((bitLen ushr shift) and 0xff).toByte()
        processBlock()
        val out = ByteArray(32)
        var j = 0
        for (s in state) {
            out[j++] = (s ushr 24).toByte()
            out[j++] = (s ushr 16).toByte()
            out[j++] = (s ushr 8).toByte()
            out[j++] = s.toByte()
        }
        return out.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun processBlock() {
        for (t in 0 until 16) {
            val b = t * 4
            w[t] = ((block[b].toInt() and 0xff) shl 24) or ((block[b + 1].toInt() and 0xff) shl 16) or
                ((block[b + 2].toInt() and 0xff) shl 8) or (block[b + 3].toInt() and 0xff)
        }
        for (t in 16 until 64) {
            val x = w[t - 15]
            val y = w[t - 2]
            val s0 = ((x ushr 7) or (x shl 25)) xor ((x ushr 18) or (x shl 14)) xor (x ushr 3)
            val s1 = ((y ushr 17) or (y shl 15)) xor ((y ushr 19) or (y shl 13)) xor (y ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (t in 0 until 64) {
            val s1 = ((e ushr 6) or (e shl 26)) xor ((e ushr 11) or (e shl 21)) xor ((e ushr 25) or (e shl 7))
            val ch = (e and f) xor (e.inv() and g)
            val t1 = h + s1 + ch + K[t] + w[t]
            val s0 = ((a ushr 2) or (a shl 30)) xor ((a ushr 13) or (a shl 19)) xor ((a ushr 22) or (a shl 10))
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    companion object {
        private val K = intArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
            0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
            0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f.toInt(), 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
            0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
        )
    }
}
