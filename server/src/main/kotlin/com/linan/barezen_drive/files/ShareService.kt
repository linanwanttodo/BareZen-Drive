package com.linan.barezen_drive.files

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.toUuidOrBadRequest
import com.linan.barezen_drive.core.dto.ShareCreateRequest
import com.linan.barezen_drive.core.dto.ShareDto
import com.linan.barezen_drive.core.dto.SharedContentsResponse
import com.linan.barezen_drive.core.dto.SharedFileDto
import com.linan.barezen_drive.core.dto.SharedFolderDto
import com.linan.barezen_drive.core.dto.SharedInfoResponse
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.ShareLinksTable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Resolved share: enough context to serve every public endpoint. */
data class ResolvedShare(
    val id: UUID,
    val fileId: UUID?,
    val folderId: UUID?,
)

object ShareService {
    private val rnd = SecureRandom()
    private const val TOKEN_BYTES = 32
    private val MIN_TTL_MS = Duration.ofHours(1).toMillis()
    private val MAX_TTL_MS = Duration.ofDays(365).toMillis()

    private fun hashToken(t: String) = MessageDigest.getInstance("SHA-256")
        .digest(t.encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        rnd.nextBytes(bytes)
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    // ---- Owner operations ----

    /** Creates a share for the given file or folder; returns the DTO carrying the
     *  one-time raw token inside its url. */
    fun createShare(userId: UUID, req: ShareCreateRequest): ShareDto {
        val fileId = req.fileId?.takeIf { it.isNotBlank() }
        val folderId = req.folderId?.takeIf { it.isNotBlank() }
        if ((fileId == null) == (folderId == null)) {
            throw ApiException.badRequest("fileId 与 folderId 必须二选一")
        }
        val ttl = req.expiresInHours
        val expiresAt: Long? = when {
            ttl == null || ttl <= 0 -> null
            else -> System.currentTimeMillis() + (ttl * 3_600_000).coerceIn(MIN_TTL_MS, MAX_TTL_MS)
        }
        return transaction(DatabaseFactory.db) {
            val targetType: String
            val targetName: String
            if (fileId != null) {
                val fid = fileId.toUuidOrBadRequest()
                val row = FilesTable.selectAll().where { (FilesTable.id eq fid) and (FilesTable.user eq userId) }.singleOrNull()
                    ?: throw ApiException.notFound("文件不存在")
                targetType = "file"
                targetName = row[FilesTable.name]
            } else {
                val gid = folderId!!.toUuidOrBadRequest()
                val row = FoldersTable.selectAll().where { (FoldersTable.id eq gid) and (FoldersTable.user eq userId) }.singleOrNull()
                    ?: throw ApiException.notFound("文件夹不存在")
                targetType = "folder"
                targetName = row[FoldersTable.name]
            }
            val id = UUID.randomUUID()
            val now = System.currentTimeMillis()
            val token = newToken()
            ShareLinksTable.insert {
                it[ShareLinksTable.id] = id
                it[user] = userId
                it[ShareLinksTable.file] = if (targetType == "file") UUID.fromString(fileId) else null
                it[folder] = if (targetType == "folder") UUID.fromString(folderId) else null
                it[tokenHash] = hashToken(token)
                it[createdAt] = now
                it[ShareLinksTable.expiresAt] = expiresAt
            }
            ShareDto(
                id.toString(),
                "/s/$token",
                targetType,
                targetName,
                expiresAt?.let { Instant.ofEpochMilli(it).toString() },
                Instant.ofEpochMilli(now).toString(),
            )
        }
    }

    /** Lists the owner's active (non-revoked, non-expired) shares; no filter
     *  returns every active share for the management screen. */
    fun listShares(userId: UUID, fileId: String?, folderId: String?): List<ShareDto> = transaction(DatabaseFactory.db) {
        val now = System.currentTimeMillis()
        val base = ShareLinksTable.selectAll().where { ShareLinksTable.user eq userId }
            .filter { it[ShareLinksTable.revokedAt] == null }
            .filter { (it[ShareLinksTable.expiresAt] ?: Long.MAX_VALUE) > now }
            .sortedByDescending { it[ShareLinksTable.createdAt] }
        val rows = when {
            fileId != null -> base.filter { it[ShareLinksTable.file]?.toString() == fileId }
            folderId != null -> base.filter { it[ShareLinksTable.folder]?.toString() == folderId }
            else -> base
        }
        rows.map { row ->
            val isFile = row[ShareLinksTable.file] != null
            val targetName = if (isFile) {
                val fid = row[ShareLinksTable.file] ?: return@map ShareDto(
                    row[ShareLinksTable.id].toString(), "/s/<token>", "file", "(已删除)",
                    row[ShareLinksTable.expiresAt]?.let { Instant.ofEpochMilli(it).toString() },
                    Instant.ofEpochMilli(row[ShareLinksTable.createdAt]).toString(),
                    row[ShareLinksTable.viewCount], row[ShareLinksTable.downloadCount],
                )
                FilesTable.selectAll().where { FilesTable.id eq fid }
                    .singleOrNull()?.get(FilesTable.name) ?: "(已删除)"
            } else {
                val gid = row[ShareLinksTable.folder] ?: return@map ShareDto(
                    row[ShareLinksTable.id].toString(), "/s/<token>", "folder", "(已删除)",
                    row[ShareLinksTable.expiresAt]?.let { Instant.ofEpochMilli(it).toString() },
                    Instant.ofEpochMilli(row[ShareLinksTable.createdAt]).toString(),
                    row[ShareLinksTable.viewCount], row[ShareLinksTable.downloadCount],
                )
                FoldersTable.selectAll().where { FoldersTable.id eq gid }
                    .singleOrNull()?.get(FoldersTable.name) ?: "(已删除)"
            }
            ShareDto(
                row[ShareLinksTable.id].toString(),
                // Token is unrecoverable from the hash; the url carries no secret here.
                "/s/<token>",
                if (isFile) "file" else "folder",
                targetName,
                row[ShareLinksTable.expiresAt]?.let { Instant.ofEpochMilli(it).toString() },
                Instant.ofEpochMilli(row[ShareLinksTable.createdAt]).toString(),
                row[ShareLinksTable.viewCount],
                row[ShareLinksTable.downloadCount],
            )
        }
    }

    /** Bumps the view counter for a share landing/listing request. */
    fun recordView(share: ResolvedShare) {
        transaction(DatabaseFactory.db) {
            ShareLinksTable.update({ ShareLinksTable.id eq share.id }) {
                with(SqlExpressionBuilder) {
                    it[viewCount] = viewCount + 1
                }
            }
        }
    }

    /** Bumps the download counter for a served file body. */
    fun recordDownload(share: ResolvedShare) {
        transaction(DatabaseFactory.db) {
            ShareLinksTable.update({ ShareLinksTable.id eq share.id }) {
                with(SqlExpressionBuilder) {
                    it[downloadCount] = downloadCount + 1
                }
            }
        }
    }

    /** Physical row delete: the link stops working immediately. */
    fun revokeShare(userId: UUID, id: UUID) {
        transaction(DatabaseFactory.db) {
            val rows = ShareLinksTable.selectAll().where { (ShareLinksTable.id eq id) and (ShareLinksTable.user eq userId) }
            if (rows.none()) throw ApiException.notFound("分享不存在")
            ShareLinksTable.deleteWhere { (ShareLinksTable.id eq id) and (ShareLinksTable.user eq userId) }
        }
    }

    // ---- Public resolution ----

    /** Token -> share context; 404 for unknown/expired/revoked tokens without
     *  distinguishing the reason (no status oracle for guessers). */
    fun resolveToken(token: String): ResolvedShare {
        val row = transaction(DatabaseFactory.db) {
            ShareLinksTable.selectAll().where { ShareLinksTable.tokenHash eq hashToken(token) }.singleOrNull()
        } ?: throw ApiException.notFound("链接无效或已过期")
        val now = System.currentTimeMillis()
        if (row[ShareLinksTable.revokedAt] != null) throw ApiException.notFound("链接无效或已过期")
        val exp = row[ShareLinksTable.expiresAt]
        if (exp != null && exp <= now) throw ApiException.notFound("链接无效或已过期")
        return ResolvedShare(row[ShareLinksTable.id], row[ShareLinksTable.file], row[ShareLinksTable.folder])
    }

    /** Public metadata for the share landing page. */
    fun sharedInfo(share: ResolvedShare): SharedInfoResponse {
        if (share.fileId != null) {
            return transaction(DatabaseFactory.db) {
                val r = FilesTable.selectAll().where { FilesTable.id eq share.fileId }.singleOrNull()
                    ?: throw ApiException.notFound("链接无效或已过期")
                SharedInfoResponse(
                    "file",
                    r[FilesTable.name],
                    r[FilesTable.id].toString(),
                    r[FilesTable.size],
                    r[FilesTable.mimeType],
                    Instant.ofEpochMilli(r[FilesTable.updatedAt]).toString(),
                )
            }
        }
        val folderId = share.folderId ?: throw ApiException.notFound("链接无效或已过期")
        return transaction(DatabaseFactory.db) {
            val r = FoldersTable.selectAll().where { FoldersTable.id eq folderId }.singleOrNull()
                ?: throw ApiException.notFound("链接无效或已过期")
            SharedInfoResponse("folder", r[FoldersTable.name])
        }
    }

    /** Lists one folder inside a folder share; the requested folder must belong
     *  to the share subtree (server-enforced boundary). */
    fun sharedContents(share: ResolvedShare, folderParam: String?): SharedContentsResponse {
        val rootId = share.folderId ?: throw ApiException.notFound("链接无效或已过期")
        return transaction(DatabaseFactory.db) {
            val parent: UUID = when {
                folderParam.isNullOrBlank() || folderParam == "root" -> rootId
                else -> {
                    val fid = folderParam.toUuidOrBadRequest()
                    val row = FoldersTable.selectAll().where { FoldersTable.id eq fid }.singleOrNull()
                        ?: throw ApiException.notFound("文件夹不存在")
                    if (!insideSubtree(fid, rootId)) throw ApiException.notFound("文件夹不存在")
                    row[FoldersTable.id]
                }
            }
            val folders = FoldersTable.selectAll().where { FoldersTable.parent eq parent }
                .map { SharedFolderDto(it[FoldersTable.id].toString(), it[FoldersTable.name]) }
                .sortedBy { it.name.lowercase() }
            val files = FilesTable.selectAll().where { FilesTable.folder eq parent }
                .map { it.toSharedFileDto() }
                .sortedBy { it.name.lowercase() }
            SharedContentsResponse(folders, files)
        }
    }

    /** File access inside a share: file shares match by id; folder shares require
     *  the file's folder chain to reach the share root. Returns FileMeta for download. */
    fun sharedFileMeta(share: ResolvedShare, fileId: UUID): FileMeta = transaction(DatabaseFactory.db) {
        val row = FilesTable.selectAll().where { FilesTable.id eq fileId }.singleOrNull()
            ?: throw ApiException.notFound("文件不存在")
        if (share.fileId != null) {
            if (row[FilesTable.id] != share.fileId) throw ApiException.notFound("文件不存在")
            return@transaction row.toFileMeta()
        }
        val folder = row[FilesTable.folder]
            ?: throw ApiException.notFound("文件不存在")
        val rootId = share.folderId ?: throw ApiException.notFound("文件不存在")
        if (!insideSubtree(folder, rootId)) throw ApiException.notFound("文件不存在")
        row.toFileMeta()
    }

    /** True when [fid] is inside the subtree rooted at [rootId] (inclusive).
     *  Walks the parent chain upward: O(depth), no recursion in SQL. */
    private fun insideSubtree(fid: UUID, rootId: UUID): Boolean {
        var cur: UUID = fid
        var guard = 0
        while (true) {
            if (cur == rootId) return true
            val row = FoldersTable.selectAll().where { FoldersTable.id eq cur }.singleOrNull() ?: return false
            val next = row[FoldersTable.parent] ?: return false
            cur = next
            if (++guard > 1000) return false
        }
    }

    private fun ResultRow.toFileMeta() = FileMeta(
        this[FilesTable.id], this[FilesTable.user], this[FilesTable.folder], this[FilesTable.name],
        this[FilesTable.size], this[FilesTable.mimeType], this[FilesTable.sha256], this[FilesTable.storageKey],
        this[FilesTable.hasThumbnail],
    )

    private fun ResultRow.toSharedFileDto() = SharedFileDto(
        this[FilesTable.id].toString(),
        this[FilesTable.name],
        this[FilesTable.size],
        this[FilesTable.mimeType],
        this[FilesTable.hasThumbnail],
    )
}
