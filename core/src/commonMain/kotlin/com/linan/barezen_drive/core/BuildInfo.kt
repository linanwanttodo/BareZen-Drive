package com.linan.barezen_drive.core

/**
 * Build identity shared by every target.
 *
 * VERSION is the single source of truth for the product version: the server
 * reports it in GET /api/version, and every client shows it in Settings.
 * Bump it in the same change that updates the Android versionName and any
 * release notes so all targets stay in step.
 */
object BuildInfo {
    const val NAME = "BareZen-Drive"
    const val VERSION = "0.0.1"

    /** Version of the client/server REST contract, independent of VERSION. */
    const val API_VERSION = 1

    /** Upstream repository, used as the default release source. */
    const val REPOSITORY_URL = "https://github.com/linanwanttodo/BareZen-Drive"

    /** Strips a leading "v" so "v1.2.3" and "1.2.3" compare as the same release. */
    fun normalize(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    /**
     * Numeric dotted comparison; a missing segment counts as 0 and any
     * non-numeric suffix (for example "-rc1") is ignored. Shared by the server
     * release check and the clients so both agree on what "newer" means.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = normalize(candidate).split('.', '-', '+')
        val b = normalize(current).split('.', '-', '+')
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (x != y) return x > y
        }
        return false
    }
}
