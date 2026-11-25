package com.linan.barezen_drive.evidence

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EvidenceKeyBoxTest {
    @Test
    fun leaseIsSingleUseAndExpiresWithoutPersistingKeys() {
        val box = EvidenceKeyBox(nowMs = { 0L })
        val dek = ByteArray(32) { 1 }
        val lease = box.issue(dek, expiresAtMs = 1_000L)
        assertContentEquals(dek, box.take(lease))
        assertNull(box.take(lease))
        val expiredBox = EvidenceKeyBox(nowMs = { 101L })
        val expired = expiredBox.issue(dek, expiresAtMs = 100L)
        assertNull(expiredBox.take(expired))
        assertFailsWith<IllegalArgumentException> { box.issue(ByteArray(31), expiresAtMs = 1_000L) }
    }
}
