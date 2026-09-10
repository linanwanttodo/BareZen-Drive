package com.linan.barezen_drive.core.dto

import kotlinx.serialization.Serializable

@Serializable data class UserDto(val id: String, val username: String, val createdAt: String)
@Serializable data class LoginRequest(val username: String, val password: String)
@Serializable data class RegisterRequest(val username: String, val password: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class LoginResponse(val accessToken: String, val refreshToken: String, val user: UserDto)
@Serializable data class RefreshResponse(val accessToken: String, val refreshToken: String)
@Serializable data class FolderDto(val id: String, val name: String, val parentId: String?, val createdAt: String, val updatedAt: String)
@Serializable data class FileDto(val id: String, val name: String, val folderId: String?, val size: Long, val mimeType: String?, val sha256: String, val createdAt: String, val updatedAt: String, val hasThumbnail: Boolean = false)
@Serializable data class ContentsResponse(val folder: FolderDto?, val folders: List<FolderDto>, val files: List<FileDto>)
@Serializable data class CreateFolderRequest(val parentId: String? = null, val name: String)
@Serializable data class RenameFolderRequest(val name: String)
@Serializable data class UpdateFileRequest(val name: String? = null, val folderId: String? = null)
@Serializable data class UploadInitRequest(val folderId: String? = null, val name: String, val size: Long, val mimeType: String? = null, val sha256: String? = null, val chunkSize: Long? = null)
@Serializable data class UploadInitResponse(val uploadId: String, val chunkSize: Long, val receivedChunks: List<Int> = emptyList(), val instantUpload: Boolean = false, val file: FileDto? = null)
@Serializable data class UploadCompleteResponse(val file: FileDto)
@Serializable data class ApiError(val code: String, val message: String)
@Serializable data class ErrorResponse(val error: ApiError)
@Serializable data class RecentFilesResponse(val files: List<FileDto>)
@Serializable data class FileLinkResponse(val url: String, val expiresAt: String)
@Serializable data class AlbumPage(val files: List<FileDto>, val nextCursor: String? = null)

// ---- Share links (read-only, files or folders) ----

/** Owner-facing create request: exactly one of fileId/folderId; null expiresInHours = permanent. */
@Serializable data class ShareCreateRequest(
    val fileId: String? = null,
    val folderId: String? = null,
    val expiresInHours: Long? = null,
)

/** Owner-facing share row. url is the /s/<token> relative path (token shown once at creation). */
@Serializable data class ShareDto(
    val id: String,
    val url: String,
    val targetType: String,
    val targetName: String,
    val expiresAt: String?,
    val createdAt: String,
    val viewCount: Long = 0,
    val downloadCount: Long = 0,
)

@Serializable data class SharesResponse(val shares: List<ShareDto>)

/** Public share metadata; deliberately leaks no owner id or content hash. */
@Serializable data class SharedInfoResponse(
    val type: String,
    val name: String,
    val fileId: String? = null,
    val size: Long? = null,
    val mimeType: String? = null,
    val updatedAt: String? = null,
)

/** Public listing of one folder inside a folder share (no hashes, no owner ids). */
@Serializable data class SharedFileDto(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String?,
    val hasThumbnail: Boolean,
)

@Serializable data class SharedFolderDto(val id: String, val name: String)

@Serializable data class SharedContentsResponse(
    val folders: List<SharedFolderDto>,
    val files: List<SharedFileDto>,
)
@Serializable data class ServerStatsDto(
    val cpuPercent: Double = -1.0,
    val memTotalBytes: Long = -1,
    val memUsedBytes: Long = -1,
    val diskTotalBytes: Long = -1,
    val diskFreeBytes: Long = -1,
    val netRxBytesPerSec: Long = -1,
    val netTxBytesPerSec: Long = -1,
    val uptimeSeconds: Long = -1,
)
