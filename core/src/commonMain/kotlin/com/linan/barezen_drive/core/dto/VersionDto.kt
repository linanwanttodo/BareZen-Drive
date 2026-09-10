package com.linan.barezen_drive.core.dto

import kotlinx.serialization.Serializable

/**
 * Response of GET /api/version.
 *
 * The server is the single update authority for every client: it reports its
 * own build and the newest release it could resolve, so clients never need
 * direct outbound network access of their own. Fields that could not be
 * resolved (offline server, GitHub rate limit) are null, and updateAvailable
 * stays false - "unknown" must never be shown as "update available".
 */
@Serializable data class VersionInfoResponse(
    val name: String,
    val serverVersion: String,
    val apiVersion: Int,
    /** Newest upstream release tag, without the leading "v"; null when unknown. */
    val latestVersion: String? = null,
    /** Human-facing release URL; null when no release could be resolved. */
    val releaseUrl: String? = null,
    /** True only when latestVersion is known and strictly newer than serverVersion. */
    val updateAvailable: Boolean = false,
)
