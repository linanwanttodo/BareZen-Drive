package com.linan.barezen_drive.s3

import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.files.FileMeta
import com.linan.barezen_drive.files.respondStoredFile
import com.linan.barezen_drive.storage.StorageRegistry
import com.linan.barezen_drive.webdav.DavPrecondition
import com.linan.barezen_drive.webdav.DavProperties
import com.linan.barezen_drive.webdav.DavTarget
import com.linan.barezen_drive.webdav.WebdavTokenRecord
import com.linan.barezen_drive.webdav.davPrecondition
import com.linan.barezen_drive.webdav.resolveDavPath
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Base64
import java.util.UUID

internal data class S3ObjectRow(val meta: FileMeta, val updatedAt: Long, val userMeta: String?)

internal suspend fun s3ObjectRow(userId: UUID, fileId: UUID): S3ObjectRow? = withContext(Dispatchers.IO) {
    transaction(DatabaseFactory.db) {
        FilesTable.select(
            FilesTable.id, FilesTable.user, FilesTable.folder, FilesTable.name, FilesTable.size,
            FilesTable.mimeType, FilesTable.sha256, FilesTable.storageKey, FilesTable.hasThumbnail,
            FilesTable.updatedAt, FilesTable.userMeta,
        ).where {
            (FilesTable.id eq fileId) and (FilesTable.user eq userId) and (FilesTable.deletedAt eq 0L)
        }.firstOrNull()?.let { r ->
            S3ObjectRow(
                FileMeta(
                    r[FilesTable.id], r[FilesTable.user], r[FilesTable.folder], r[FilesTable.name],
                    r[FilesTable.size], r[FilesTable.mimeType], r[FilesTable.sha256], r[FilesTable.storageKey],
                    r[FilesTable.hasThumbnail],
                ),
                r[FilesTable.updatedAt], r[FilesTable.userMeta],
            )
        }
    }
}

/** 对象级分发。key 为空时不该到这里（路由已分开），但兜住尾随斜杠的形状。 */
internal suspend fun ApplicationCall.s3ObjectDispatch(token: WebdavTokenRecord) {
    s3UnsupportedSubresource()
    when (request.httpMethod.value.uppercase()) {
        "GET", "HEAD" -> s3GetObject(token)
        "PUT" -> s3NotYetImplemented()      // Task 14
        "DELETE" -> s3NotYetImplemented()   // Task 14
        "POST" -> s3NotYetImplemented()     // Task 16
        else -> throw S3Exception("NotImplemented", HttpStatusCode.NotImplemented, "不支持的方法")
    }
}

internal suspend fun ApplicationCall.s3GetObject(token: WebdavTokenRecord) {
    val bucketName = s3BucketName()
    if (s3BucketId(token.userId, bucketName) == null) {
        throw S3Exception("NoSuchBucket", HttpStatusCode.NotFound, "桶不存在")
    }
    val segments = s3KeySegments(s3RawKey())
        ?: throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "键非法")
    val target = resolveDavPath(token.userId, listOf(bucketName) + segments)
    val fileId = when (target) {
        is DavTarget.Blob -> target.id
        else -> throw S3Exception("NoSuchKey", HttpStatusCode.NotFound, "键不存在")
    }
    val row = s3ObjectRow(token.userId, fileId)
        ?: throw S3Exception("NoSuchKey", HttpStatusCode.NotFound, "键不存在")

    val etag = "\"${row.meta.sha256}\""
    when (davPrecondition(etag, row.updatedAt)) {
        DavPrecondition.Proceed -> Unit
        DavPrecondition.NotModified -> {
            response.header(HttpHeaders.ETag, etag)
            response.header(HttpHeaders.LastModified, DavProperties.httpDate(row.updatedAt))
            respond(HttpStatusCode.NotModified)
            return
        }
        DavPrecondition.Failed -> throw S3Exception("PreconditionFailed", HttpStatusCode.PreconditionFailed, "至少一个条件头不成立")
    }
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.LastModified, DavProperties.httpDate(row.updatedAt))
    for ((key, value) in s3MetaHeaders(row.userMeta)) {
        response.header("x-amz-meta-$key", value)
    }
    if (request.headers["x-amz-checksum-mode"]?.equals("ENABLED", ignoreCase = true) == true) {
        val raw = row.meta.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        response.header("x-amz-checksum-sha256", Base64.getEncoder().encodeToString(raw))
    }
    val (storage, key) = StorageRegistry.resolve(row.meta.storageKey)
    // HEAD 也走 respondStoredFile：Ktor 对 HEAD 不写 body，但 Content-Length 与
    // 416/404 的判定要与 GET 完全一致，手写一份必然漂移。
    respondStoredFile(storage, row.meta.copy(storageKey = key))
}