package com.linan.barezen_drive.evidence

import java.text.Normalizer

object EvidencePasswordPolicy {
    const val MIN_LENGTH = 12
    const val MAX_LENGTH = 128

    fun normalize(password: String): String =
        Normalizer.normalize(password, Normalizer.Form.NFC)

    fun isValid(password: String): Boolean {
        val normalized = normalize(password)
        return normalized.codePointCount(0, normalized.length) in MIN_LENGTH..MAX_LENGTH
    }

    fun validate(password: String) {
        require(isValid(password)) { "password length must be 12..128 characters" }
    }
}

class AttemptPolicy(
    private var failures: Int = 0,
    private var cooldownUntilMs: Long = 0L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun remainingCooldownMs(): Long = (cooldownUntilMs - nowMs()).coerceAtLeast(0L)

    @Synchronized
    fun isCoolingDown(): Boolean = remainingCooldownMs() > 0L

    @Synchronized
    fun assertAllowed() {
        if (cooldownUntilMs != 0L && remainingCooldownMs() == 0L) {
            failures = 0
            cooldownUntilMs = 0L
        }
        check(!isCoolingDown()) { "password cooldown active" }
    }

    @Synchronized
    fun recordFailure() {
        failures += 1
        if (failures >= 5) cooldownUntilMs = nowMs() + 60_000L
    }

    @Synchronized
    fun recordSuccess() {
        failures = 0
        cooldownUntilMs = 0L
    }

    fun snapshot(): Pair<Int, Long> = failures to cooldownUntilMs
}
