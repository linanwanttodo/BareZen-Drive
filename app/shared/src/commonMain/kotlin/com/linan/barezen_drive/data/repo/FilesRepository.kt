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
import com.linan.barezen_drive.core.dto.SharedInfoResponse
import com.linan.barezen_drive.core.dto.UploadInitRequest
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.core.dto.VersionInfoResponse
import com.linan.barezen_drive.core.dto.RegistrationStatusDto
import com.linan.barezen_drive.data.api.ApiClient
import io.ktor.utils.io.ByteReadChannel

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

    suspend fun contents(folderId: String): Result<ContentsResponse> = api.contents(folderId)
    suspend fun recentFiles(limit: Int = 12): Result<RecentFilesResponse> = api.recentFiles(limit)
    suspend fun album(limit: Int = 200, before: String? = null, root: String? = null): Result<AlbumPage> =
        api.album(limit, before, root)
    suspend fun serverStats(): Result<ServerStatsDto> = api.serverStats()
    suspend fun versionInfo(): Result<VersionInfoResponse> = api.versionInfo()
    suspend fun registrationStatus(): Result<RegistrationStatusDto> = api.registrationStatus()
    suspend fun setRegistrationOpen(open: Boolean): Result<RegistrationStatusDto> = api.setRegistrationOpen(open)
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

    // ---- Public share access (no authentication) ----

    suspend fun sharedInfo(token: String): Result<SharedInfoResponse> = api.sharedInfo(token)
    suspend fun sharedContents(token: String, folderId: String? = null): Result<SharedContentsResponse> =
        api.sharedContents(token, folderId)
    suspend fun sharedThumbnailBytes(token: String, fileId: String): Result<ByteArray> =
        api.sharedThumbnailBytes(token, fileId)
    suspend fun sharedDownload(token: String, fileId: String, range: LongRange? = null): io.ktor.utils.io.ByteReadChannel =
        api.sharedDownload(token, fileId, range)
    suspend fun sharedPreviewBytes(token: String, fileId: String): ByteArray =
        api.sharedPreviewBytes(token, fileId)
    fun sharedContentUrl(token: String, fileId: String): String = api.sharedContentUrl(token, fileId)
}
