package com.linan.barezen_drive.data.repo

import com.linan.barezen_drive.core.dto.AlbumPage
import com.linan.barezen_drive.core.dto.ContentsResponse
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.FileLinkResponse
import com.linan.barezen_drive.core.dto.ServerStatsDto
import com.linan.barezen_drive.core.dto.FolderDto
import com.linan.barezen_drive.core.dto.RecentFilesResponse
import com.linan.barezen_drive.core.dto.ShareDto
import com.linan.barezen_drive.core.dto.SharesResponse
import com.linan.barezen_drive.core.dto.SharedContentsResponse
import com.linan.barezen_drive.core.dto.SharedFileDto
import com.linan.barezen_drive.core.dto.SharedFolderDto
import com.linan.barezen_drive.core.dto.SharedInfoResponse
import com.linan.barezen_drive.core.dto.TrashResponse
import com.linan.barezen_drive.core.dto.UploadInitRequest
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.core.dto.VersionInfoResponse
import com.linan.barezen_drive.core.dto.AdminUsersResponse
import com.linan.barezen_drive.core.dto.UserDto
import com.linan.barezen_drive.core.dto.RegistrationStatusDto
import com.linan.barezen_drive.core.dto.WebdavTokenCreatedResponse
import com.linan.barezen_drive.core.dto.WebdavTokensResponse
import com.linan.barezen_drive.data.api.ApiClient
import io.ktor.utils.io.ByteReadChannel

/**
 * How many entries one shared-folder screen is asked to hold. Past this the page
 * walk stops: the listing was never meant to be a directory the size of a disk, and
 * a Compose list that long is unusable whatever the server is willing to answer.
 */
private const val SHARED_LISTING_LIMIT = 20_000

/**
 * Upload-facing API used by the upload pipeline (Task 10). All methods return
 * kotlin.Result; on failure exceptionOrNull() is an ApiFailure.
 */
interface UploadApi {
    suspend fun init(req: UploadInitRequest): Result<UploadInitResponse>
    suspend fun putChunk(id: String, index: Int, bytes: ByteArray, sha: String?): Result<Unit>
    suspend fun complete(id: String): Result<FileDto>
    suspend fun abort(id: String): Result<Unit>
    suspend fun putThumbnail(id: String, bytes: ByteArray): Result<Unit>
}

/**
 * Folder/file operations plus the UploadApi implementation. Thin delegation
 * over ApiClient; all failures are ApiFailure-typed via the client.
 */
class FilesRepository(private val api: ApiClient) : UploadApi {
    /** Server base URL, e.g. "http://192.168.1.10:8080" (no trailing slash). */
    val baseUrl: String get() = api.baseUrl

    override suspend fun init(req: UploadInitRequest) = api.uploadInit(req)
    override suspend fun putChunk(id: String, index: Int, bytes: ByteArray, sha: String?) =
        api.uploadChunk(id, index, bytes, sha)
    override suspend fun complete(id: String) = api.uploadComplete(id)
    override suspend fun abort(id: String) = api.uploadAbort(id)
    override suspend fun putThumbnail(id: String, bytes: ByteArray) = api.putThumbnail(id, bytes)

    suspend fun contents(folderId: String, limit: Int? = null, cursor: String? = null): Result<ContentsResponse> =
        api.contents(folderId, limit, cursor)
    suspend fun recentFiles(limit: Int = 12): Result<RecentFilesResponse> = api.recentFiles(limit)
    suspend fun album(limit: Int = 200, before: String? = null, root: String? = null, favorite: Boolean = false, archived: Boolean = false): Result<AlbumPage> =
        api.album(limit, before, root, favorite, archived)
    suspend fun serverStats(): Result<ServerStatsDto> = api.serverStats()
    suspend fun versionInfo(): Result<VersionInfoResponse> = api.versionInfo()
    suspend fun registrationStatus(): Result<RegistrationStatusDto> = api.registrationStatus()
    suspend fun setRegistrationOpen(open: Boolean): Result<RegistrationStatusDto> = api.setRegistrationOpen(open)
    suspend fun adminUsers(): Result<AdminUsersResponse> = api.adminUsers()
    suspend fun adminDeleteUser(id: String): Result<Unit> = api.adminDeleteUser(id)
    suspend fun me(): Result<UserDto> = api.me()

    suspend fun search(query: String): Result<RecentFilesResponse> = api.search(query)

    suspend fun putAvatar(bytes: ByteArray): Result<Unit> = api.putAvatar(bytes)

    suspend fun avatarBytes(userId: String): Result<ByteArray> = api.avatarBytes(userId)
    suspend fun ping(): Long = api.ping()
    suspend fun thumbnailBytes(id: String): Result<ByteArray> = api.thumbnailBytes(id)
    suspend fun fileLink(id: String, ttl: Int? = null): Result<FileLinkResponse> = api.fileLink(id, ttl)
    suspend fun createFolder(parentId: String?, name: String): Result<FolderDto> =
        api.createFolder(parentId, name)
    suspend fun renameFolder(id: String, name: String): Result<FolderDto> = api.renameFolder(id, name)
    suspend fun deleteFolder(id: String): Result<Unit> = api.deleteFolder(id)
    suspend fun updateFile(id: String, name: String?, folderId: String?): Result<FileDto> =
        api.updateFile(id, name, folderId)
    suspend fun deleteFile(id: String): Result<Unit> = api.deleteFile(id)

    // ---- Favorites / archive / trash ----

    suspend fun setFavorite(id: String, favorite: Boolean): Result<FileDto> = api.setFavorite(id, favorite)
    suspend fun setArchived(id: String, archived: Boolean): Result<FileDto> = api.setArchived(id, archived)

    // ---- File versions (overwrite history) ----

    suspend fun fileVersions(id: String): Result<List<com.linan.barezen_drive.core.dto.FileVersionDto>> = api.fileVersions(id)
    suspend fun restoreFileVersion(id: String, versionId: String): Result<FileDto> = api.restoreFileVersion(id, versionId)
    suspend fun deleteFileVersion(id: String, versionId: String): Result<Unit> = api.deleteFileVersion(id, versionId)
    suspend fun trash(limit: Int? = null, cursor: String? = null): Result<TrashResponse> = api.trash(limit, cursor)
    suspend fun restoreFromTrash(id: String): Result<FileDto> = api.restoreFromTrash(id)
    suspend fun deleteForever(id: String): Result<Unit> = api.deleteForever(id)
    suspend fun emptyTrash(): Result<Unit> = api.emptyTrash()

    suspend fun download(id: String, range: LongRange? = null): ByteReadChannel = api.download(id, range)
    suspend fun previewBytes(id: String): ByteArray = api.previewBytes(id)

    // ---- Share links ----

    suspend fun createShare(fileId: String? = null, folderId: String? = null, expiresInHours: Long? = null): Result<ShareDto> =
        api.createShare(fileId, folderId, expiresInHours)
    suspend fun listShares(fileId: String? = null, folderId: String? = null): Result<SharesResponse> =
        api.listShares(fileId, folderId)
    suspend fun revokeShare(id: String): Result<Unit> = api.revokeShare(id)

    /** Full share URL for copy/paste: baseUrl + /s/<token>. */
    fun shareUrl(path: String): String = api.baseUrl + path

    // ---- WebDAV app passwords (mounted drives) ----

    suspend fun createWebdavToken(label: String, readOnly: Boolean): Result<WebdavTokenCreatedResponse> =
        api.createWebdavToken(label, readOnly)
    suspend fun listWebdavTokens(): Result<WebdavTokensResponse> =
        api.listWebdavTokens()
    suspend fun revokeWebdavToken(id: String): Result<Unit> =
        api.revokeWebdavToken(id)

    // ---- Public share access (no authentication) ----

    suspend fun sharedInfo(token: String): Result<SharedInfoResponse> = api.sharedInfo(token)

    /**
     * A shared folder's whole listing, assembled from the pages the server hands out.
     *
     * The endpoint used to answer with every entry of the directory in one response,
     * and it is public - so one anonymous request could cost the server whatever the
     * owner had shared. It now answers a page at a time; walking those pages here is
     * what keeps the share screen showing a complete listing.
     *
     * The two positions advance independently, and a list that is done keeps sending
     * its last position instead of nothing: an absent cursor means "start over", which
     * would serve the finished half a second time. [SHARED_LISTING_LIMIT] is the
     * ceiling on how many entries one screen is asked to hold.
     */
    suspend fun sharedContents(token: String, folderId: String? = null): Result<SharedContentsResponse> {
        val folders = ArrayList<SharedFolderDto>()
        val files = ArrayList<SharedFileDto>()
        var folderCursor: String? = null
        var fileCursor: String? = null
        var moreFolders = true
        var moreFiles = true
        while ((moreFolders || moreFiles) && folders.size + files.size < SHARED_LISTING_LIMIT) {
            val page = api.sharedContents(token, folderId, folderCursor, fileCursor)
            if (page.isFailure) return page
            val value = page.getOrThrow()
            if (moreFolders) folders += value.folders
            if (moreFiles) files += value.files
            value.folderCursor?.let { folderCursor = it }
            value.fileCursor?.let { fileCursor = it }
            moreFolders = value.folderCursor != null
            moreFiles = value.fileCursor != null
        }
        return Result.success(SharedContentsResponse(folders, files))
    }
    suspend fun sharedDownload(token: String, fileId: String, range: LongRange? = null): ByteReadChannel =
        api.sharedDownload(token, fileId, range)
    suspend fun sharedPreviewBytes(token: String, fileId: String): ByteArray =
        api.sharedPreviewBytes(token, fileId)
}
