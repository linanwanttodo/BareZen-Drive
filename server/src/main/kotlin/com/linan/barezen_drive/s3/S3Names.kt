package com.linan.barezen_drive.s3

import com.linan.barezen_drive.api.isUniqueViolation
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.UploadChunksTable
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.webdav.hasUnpairedSurrogate
import com.linan.barezen_drive.webdav.percentDecode
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

private val BUCKET_CHARS = Regex("^[a-z0-9.-]+$")
private val IPV4_SHAPE = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
private const val MAX_KEY_BYTES = 1024
private const val MAX_SEGMENT_CHARS = 255

/** spec §5.2 的桶名方言。名字不合法的顶层文件夹在 S3 侧不存在。 */
internal fun s3BucketNameOk(name: String): Boolean {
    if (name.lowercase() in S3_RESERVED_BUCKETS) return false
    if (name.length !in 3..63) return false
    if (!BUCKET_CHARS.matches(name)) return false
    if (!name.first().isLetterOrDigit() || !name.last().isLetterOrDigit()) return false
    if (".." in name) return false
    if (IPV4_SHAPE.matches(name)) return false
    return true
}

/**
 * 键的段级解析。复用 WebDAV 的百分号解码与拒绝规则（段内不得解出分隔符、
 * 非法 UTF-8、孤立代理项），但空段在这里是**拒绝**而不是丢弃：S3 的键可以
 * 含 "//"，本项目的文件夹模型不能，悄悄折叠会把两个不同的键映射到同一个文件。
 */
internal fun s3KeySegments(rawKey: String): List<String>? {
    if (rawKey.isEmpty()) return null
    val parts = rawKey.split('/')
    val out = ArrayList<String>(parts.size)
    for (part in parts) {
        if (part.isEmpty()) return null
        val decoded = percentDecode(part) ?: return null
        if (decoded.isEmpty() || decoded.contains('/')) return null
        if (hasUnpairedSurrogate(decoded)) return null
        if (decoded == "." || decoded == "..") return null
        if (decoded.length > MAX_SEGMENT_CHARS) return null
        if (decoded.any { it.code < 0x20 || it.code == 0x7F }) return null
        out += decoded
    }
    // 上限是整键的 UTF-8 字节数，不是字符数：中文一个字三字节，按字符算会放行
    // 三倍长度的键，而 files.name 只有 varchar(255)、整键还要参与索引。
    if (s3KeyOf(out).toByteArray(Charsets.UTF_8).size > MAX_KEY_BYTES) return null
    return out
}

internal fun s3KeyOf(segments: List<String>): String = segments.joinToString("/")

/** 桶 ⇔ 顶层文件夹且名字合法。名字不合法的顶层文件夹在这里就是"不存在"。 */
internal suspend fun s3BucketId(userId: UUID, name: String): UUID? = withContext(Dispatchers.IO) {
    if (!s3BucketNameOk(name)) return@withContext null
    transaction(DatabaseFactory.db) {
        FoldersTable.select(FoldersTable.id).where {
            (FoldersTable.user eq userId) and (FoldersTable.parent.isNull()) and (FoldersTable.name eq name)
        }.firstOrNull()?.get(FoldersTable.id)
    }
}

/**
 * 逐级建隐式父目录。每级用 (user,parent,name) 唯一索引兜底并发：撞上唯一冲突
 * 就重读一次，因为那意味着另一个写入者刚建好了我们要的那一级。某级同名的是
 * 活文件则无法继续——对象与文件夹不能同名；回收站里的同名文件不挡路。
 */
internal suspend fun s3EnsureParents(userId: UUID, bucketId: UUID, segments: List<String>): UUID =
    withContext(Dispatchers.IO) {
        var parent = bucketId
        for (i in 0 until segments.size - 1) {
            val name = segments[i]
            val current = parent
            parent = transaction(DatabaseFactory.db) {
                val existing = FoldersTable.select(FoldersTable.id).where {
                    (FoldersTable.user eq userId) and (FoldersTable.parent eq current) and
                        (FoldersTable.name eq name)
                }.firstOrNull()
                if (existing != null) return@transaction existing[FoldersTable.id]
                val fileClash = FilesTable.select(FilesTable.id).where {
                    (FilesTable.user eq userId) and (FilesTable.folder eq current) and
                        (FilesTable.name eq name) and (FilesTable.deletedAt eq 0L)
                }.firstOrNull()
                if (fileClash != null) {
                    throw S3Exception("InvalidRequest", HttpStatusCode.Conflict, "路径中同名的是文件")
                }
                val id = UUID.randomUUID()
                try {
                    FoldersTable.insert {
                        it[FoldersTable.id] = id; it[FoldersTable.user] = userId
                        it[FoldersTable.parent] = current; it[FoldersTable.name] = name
                    }
                    id
                } catch (e: Exception) {
                    if (!e.isUniqueViolation()) throw e
                    FoldersTable.select(FoldersTable.id).where {
                        (FoldersTable.user eq userId) and (FoldersTable.parent eq current) and
                            (FoldersTable.name eq name)
                    }.first()[FoldersTable.id]
                }
            }
        }
        parent
    }

/**
 * 从 [folderId] 向上剪掉空目录，到桶目录为止（桶自己永不剪，否则删掉最后一个
 * 对象后桶会消失，rclone 会以为桶没了）。"空"= 没有子目录、没有任何状态的文件
 * （含回收站）、没有未完成的上传会话。已完成/已取消的会话不再往目录里写东西，
 * 算进去会让刚 PUT 过的目录在会话过期前一直剪不掉。
 */
internal suspend fun s3PruneEmptyAncestors(userId: UUID, folderId: UUID?, bucketId: UUID) {
    withContext(Dispatchers.IO) {
        var current = folderId
        while (current != null && current != bucketId) {
            val id = current
            val next = transaction(DatabaseFactory.db) {
                val row = FoldersTable.select(FoldersTable.parent).where {
                    (FoldersTable.id eq id) and (FoldersTable.user eq userId)
                }.firstOrNull() ?: return@transaction null
                val hasChildren = FoldersTable.select(FoldersTable.id).where { FoldersTable.parent eq id }.any()
                val hasFiles = FilesTable.select(FilesTable.id).where { FilesTable.folder eq id }.any()
                val hasOpenSessions = UploadSessionsTable.select(UploadSessionsTable.id)
                    .where { (UploadSessionsTable.folder eq id) and (UploadSessionsTable.status eq "open") }.any()
                if (hasChildren || hasFiles || hasOpenSessions) return@transaction null
                // 已完成/已取消的会话行仍按 FK 指着这个目录，不先清掉目录就删不动
                // （与 FileService.deleteFolder 同一处理）。
                val finished = UploadSessionsTable.select(UploadSessionsTable.id)
                    .where { UploadSessionsTable.folder eq id }.map { it[UploadSessionsTable.id] }
                if (finished.isNotEmpty()) {
                    UploadChunksTable.deleteWhere { UploadChunksTable.session inList finished }
                    UploadSessionsTable.deleteWhere { UploadSessionsTable.id inList finished }
                }
                FoldersTable.deleteWhere { FoldersTable.id eq id }
                row[FoldersTable.parent]
            }
            current = next
        }
    }
}