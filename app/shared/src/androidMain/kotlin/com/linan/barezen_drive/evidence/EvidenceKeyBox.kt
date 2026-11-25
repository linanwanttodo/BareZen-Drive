package com.linan.barezen_drive.evidence

import java.util.concurrent.ConcurrentHashMap

class EvidenceKeyBox(private val nowMs: () -> Long = System::currentTimeMillis) {
    private data class Lease(val dek: ByteArray, val expiresAtMs: Long)
    private val leases = ConcurrentHashMap<String, Lease>()

    fun issue(dek: ByteArray, expiresAtMs: Long): String {
        require(dek.size == 32)
        val id = EvidenceCrypto.randomBytes(16).toHex()
        leases[id] = Lease(dek.copyOf(), expiresAtMs)
        return id
    }

    fun take(id: String): ByteArray? {
        val lease = leases.remove(id) ?: return null
        if (nowMs() >= lease.expiresAtMs) {
            lease.dek.fill(0)
            return null
        }
        return lease.dek
    }

    fun clear() {
        leases.values.forEach { it.dek.fill(0) }
        leases.clear()
    }
}
