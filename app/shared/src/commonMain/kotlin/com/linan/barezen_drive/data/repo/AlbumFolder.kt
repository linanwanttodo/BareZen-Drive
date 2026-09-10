package com.linan.barezen_drive.data.repo

import com.linan.barezen_drive.core.dto.ContentsResponse
import com.linan.barezen_drive.core.dto.FolderDto

/**
 * The dedicated "相册" folder tree. Photos uploaded from the album tab land in
 * a per-device subfolder (device name) so each client's photos stay grouped
 * while the album timeline still walks the whole subtree via the album API.
 *
 * createIfMissing is idempotent: a 409 NAME_CONFLICT on the create call means
 * another client created it concurrently, and we then re-list to find it.
 */
object AlbumFolder {
    const val ROOT_NAME = "相册"

    /**
     * Resolves (or creates) the album root folder, returns null when the
     * server is unreachable or listing fails (callers show an error state).
     */
    suspend fun resolve(repo: FilesRepository, deviceName: String): String? {
        val root = findOrCreateRoot(repo) ?: return null
        return findOrCreateChild(repo, root, deviceName)
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
