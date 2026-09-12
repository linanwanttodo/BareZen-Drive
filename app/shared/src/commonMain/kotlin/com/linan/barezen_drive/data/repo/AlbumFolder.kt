package com.linan.barezen_drive.data.repo

import com.linan.barezen_drive.platform.InstallChannel
import com.linan.barezen_drive.platform.installChannel

/**
 * The dedicated album folder tree (rooted at the localized album folder name,
 * see ROOT_NAME). Photos uploaded from the album tab land in
 * a per-device subfolder (device name) so each client's photos stay grouped
 * while the album timeline still walks the whole subtree via the album API.
 *
 * createIfMissing is idempotent: a 409 NAME_CONFLICT on the create call means
 * another client created it concurrently, and we then re-list to find it.
 */
object AlbumFolder {
    const val ROOT_NAME = "相册"

    /**
     * Resolves (or creates) this device's album folder. The nesting mirrors a
     * phone gallery across machines: Album / <platform> / <device> / <phone
     * album>; photos land only at the device level, never on the platform
     * level. Returns null when the server is unreachable.
     */
    suspend fun resolve(repo: FilesRepository, deviceName: String, legacyName: String? = null): String? {
        val platform = resolvePlatform(repo) ?: return null
        // Name migration: early builds used the raw factory model code as the
        // folder name ("2210132C"). When a friendly name is set and the legacy
        // folder still exists, rename it instead of forking a second tree.
        if (!legacyName.isNullOrBlank() && legacyName != deviceName) {
            val platformContents = repo.contents(platform).getOrNull() ?: return findOrCreateChild(repo, platform, deviceName)
            val friendly = platformContents.folders.firstOrNull { it.name == deviceName }
            val legacy = platformContents.folders.firstOrNull { it.name == legacyName }
            if (friendly == null && legacy != null) {
                repo.renameFolder(legacy.id, deviceName).getOrThrow()
            }
        }
        return findOrCreateChild(repo, platform, deviceName)
    }

    /** The Album root folder - the "all devices" scope for the timeline. */
    /**
     * The phone-album category folder inside a device folder, created on
     * demand - only uploads that carry a source album name ever create one.
     */
    suspend fun resolveCategory(repo: FilesRepository, deviceFolderId: String, category: String): String? =
        findOrCreateChild(repo, deviceFolderId, category)

    /**
     * Every device folder that actually exists, across all platforms - the
     * album page's device switcher lists exactly these (folders are created
     * by an upload, so a device shows up once it has photos).
     */
    suspend fun listDevices(repo: FilesRepository): List<Pair<String, String>> {
        val root = findOrCreateRoot(repo) ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        val platforms = repo.contents(root).getOrNull()?.folders ?: return emptyList()
        for (platform in platforms) {
            val devices = repo.contents(platform.id).getOrNull() ?: continue
            // Field order note: FolderDto is (id, name) - destructure by position and
// this swaps into (uuid -> name) pairs, which is exactly the dropdown bug.
devices.folders.forEach { folder -> out += folder.name to folder.id }
        }
        return out
    }

    /** The platform folder (Android / Web / iOS / Desktop) under the root. */
    suspend fun resolvePlatform(repo: FilesRepository): String? {
        val root = findOrCreateRoot(repo) ?: return null
        return findOrCreateChild(repo, root, platformName())
    }

    fun platformName(): String = when (installChannel) {
        InstallChannel.ANDROID -> "Android"
        InstallChannel.IOS -> "iOS"
        InstallChannel.DESKTOP -> "Desktop"
        InstallChannel.WEB -> "Web"
    }

    private suspend fun findOrCreateRoot(repo: FilesRepository): String? {
        val contents = repo.contents("root").getOrNull() ?: return null
        contents.folders.firstOrNull { it.name == ROOT_NAME }?.let { return it.id }
        // Not found: create it. A concurrent sibling client may win the race;
        // re-listing resolves to the same folder either way.
        repo.createFolder(null, ROOT_NAME).fold(
            onSuccess = { return it.id },
            onFailure = {
                val again = repo.contents("root").getOrNull()
                return again?.folders?.firstOrNull { it.name == ROOT_NAME }?.id
            },
        )
    }

    private suspend fun findOrCreateChild(repo: FilesRepository, parentId: String, name: String): String? {
        val contents = repo.contents(parentId).getOrNull() ?: return null
        contents.folders.firstOrNull { it.name == name }?.let { return it.id }
        repo.createFolder(parentId, name).fold(
            onSuccess = { return it.id },
            onFailure = {
                val again = repo.contents(parentId).getOrNull()
                return again?.folders?.firstOrNull { it.name == name }?.id
            },
        )
    }
}
