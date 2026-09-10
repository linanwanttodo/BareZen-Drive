package com.linan.barezen_drive.core

/**
 * Build identity shared by every target, plus the version comparison both the
 * server release check and the clients use.
 *
 * The constant values live in the generated [BuildConstants] (produced by
 * :core:generateBuildConstants from the `version` property in
 * gradle.properties), so NAME/VERSION/API_VERSION/REPOSITORY_URL have exactly
 * one source. Bump that one property to release; nothing here needs editing.
 */
object BuildInfo {
    const val NAME = BuildConstants.NAME
    const val VERSION = BuildConstants.VERSION
    const val API_VERSION = BuildConstants.API_VERSION
    const val REPOSITORY_URL = BuildConstants.REPOSITORY_URL

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

    /**
     * Android versionCode derived from the product version, so the two cannot
     * drift: major*10000 + minor*100 + patch. Monotonic for normal releases.
     */
    fun androidVersionCode(version: String = VERSION): Int {
        val parts = normalize(version).split('.', '-', '+')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return major * 10_000 + minor * 100 + patch
    }
}
