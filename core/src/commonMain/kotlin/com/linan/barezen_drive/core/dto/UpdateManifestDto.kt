package com.linan.barezen_drive.core.dto

import kotlinx.serialization.Serializable

/**
 * One downloadable build in the update manifest.
 *
 * platform/arch/kind describe what the file is so a client can pick its own
 * package: platform is the target ("android", "web", "server", "linux",
 * "windows", "macos", "ios"), arch is the CPU or device family ("any",
 * "amd64", "arm64", ...), and kind is the file format ("apk", "zip", "tar.gz",
 * "deb", "rpm", "msi", "dmg", "ipa", ...).
 */
@Serializable data class UpdateAssetDto(
    val platform: String,
    val arch: String,
    val kind: String,
    val url: String,
    val sha256: String,
    val size: Long,
)

/** Container image metadata in the manifest (multi-arch). */
@Serializable data class UpdateDockerDto(
    val image: String,
    val tags: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
)

/**
 * Release manifest (update.json) published next to the GitHub Release.
 *
 * The server fetches this instead of the GitHub API: it is a stable,
 * unauthenticated URL with no rate limit, and it carries per-platform download
 * links so clients can fetch the right package directly. Older servers and
 * clients that only read tag_name/html_url remain compatible because the
 * server maps this into [VersionInfoResponse].
 */
@Serializable data class UpdateManifestDto(
    val name: String,
    val version: String,
    val apiVersion: Int = 1,
    val releasedAt: String? = null,
    val releaseNotesUrl: String? = null,
    val assets: List<UpdateAssetDto> = emptyList(),
    val docker: UpdateDockerDto? = null,
)
