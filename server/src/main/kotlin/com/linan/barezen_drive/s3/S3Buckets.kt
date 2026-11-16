package com.linan.barezen_drive.s3

import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.files.FileService
import com.linan.barezen_drive.files.FileTree
import com.linan.barezen_drive.files.deleteStoredBlobs
import com.linan.barezen_drive.storage.StorageRegistry
import com.linan.barezen_drive.webdav.DavProperties
import com.linan.barezen_drive.webdav.WebdavTokenRecord
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

internal val S3_UNSUPPORTED_SUBRESOURCES = setOf(
    "acl", "policy", "tagging", "cors", "lifecycle", "website", "logging", "notification",
    "replication", "encryption", "object-lock", "legal-hold", "retention", "restore",
    "select", "torrent", "attributes", "versionId",
)

private const val S3_NS = "http://s3.amazonaws.com/doc/2006-03-01/"

internal fun ApplicationCall.s3UnsupportedSubresource() {
    for (name in request.queryParameters.names()) {
        // 清单里保留 S3 的规范拼写（versionId 是驼峰），所以比较必须忽略大小写：
        // 把入参小写化再去查含驼峰的集合，versionId 永远匹配不上。
        if (S3_UNSUPPORTED_SUBRESOURCES.any { it.equals(name, ignoreCase = true) }) {
            throw S3Exception("NotImplemented", HttpStatusCode.NotImplemented, "不支持 $name")
        }
    }
}

internal suspend fun ApplicationCall.s3RespondXml(body: String, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n$body", ContentType.Application.Xml, status)
}

private fun ApplicationCall.hasQuery(name: String): Boolean =
    request.queryParameters.names().any { it.equals(name, ignoreCase = true) }

/** 桶级分发。对象级（带 key）的分发在 S3Objects/S3Multipart。 */
internal suspend fun ApplicationCall.s3BucketDispatch(token: WebdavTokenRecord) {
    s3GuardReservedBucket()
    s3UnsupportedSubresource()
    val name = s3BucketName()
    when (request.httpMethod.value.uppercase()) {
        "GET" -> when {
            hasQuery("location") -> s3BucketLocation(token, name)
            hasQuery("versioning") -> s3BucketVersioning(token, name)
            hasQuery("uploads") -> s3BucketUploads(token, name)
            hasQuery("list-type") -> s3ListObjects(token)
            else -> throw S3Exception("InvalidRequest", HttpStatusCode.BadRequest, "只支持 ListObjectsV2（list-type=2）")
        }
        "HEAD" -> s3HeadBucket(token, name)
        "PUT" -> {
            if (hasQuery("versioning")) throw S3Exception("NotImplemented", HttpStatusCode.NotImplemented, "不支持版本控制")
            s3Writable(token)
            s3CreateBucket(token, name)
        }
        "DELETE" -> { s3Writable(token); s3DeleteBucket(token, name) }
        "POST" -> {
            s3Writable(token)
            if (hasQuery("delete")) s3DeleteObjects(token)
            else throw S3Exception("NotImplemented", HttpStatusCode.NotImplemented, "不支持的 POST")
        }
        else -> throw S3Exception("NotImplemented", HttpStatusCode.NotImplemented, "不支持的方法")
    }
}

private suspend fun ApplicationCall.s3RequireBucket(userId: UUID, name: String): UUID =
    s3BucketId(userId, name) ?: throw S3Exception("NoSuchBucket", HttpStatusCode.NotFound, "桶不存在")

internal suspend fun ApplicationCall.s3ListBuckets(token: WebdavTokenRecord) = withContext(Dispatchers.IO) {
    val rows = transaction(DatabaseFactory.db) {
        val username = UsersTable.select(UsersTable.username).where { UsersTable.id eq token.userId }
            .first()[UsersTable.username]
        val buckets = FoldersTable.select(FoldersTable.name, FoldersTable.createdAt).where {
            (FoldersTable.user eq token.userId) and (FoldersTable.parent.isNull())
        }.orderBy(FoldersTable.name to SortOrder.ASC)
            .map { it[FoldersTable.name] to it[FoldersTable.createdAt] }
            .filter { s3BucketNameOk(it.first) }
        buckets to username
    }
    val (buckets, username) = rows
    s3RespondXml(buildString {
        append("<ListAllMyBucketsResult xmlns=\"").append(S3_NS).append("\">")
        append("<Owner><ID>").append(token.userId).append("</ID><DisplayName>")
        append(DavProperties.xmlEscaped(username)).append("</DisplayName></Owner><Buckets>")
        for ((name, created) in buckets) {
            append("<Bucket><Name>").append(DavProperties.xmlEscaped(name)).append("</Name><CreationDate>")
            append(s3Timestamp(created)).append("</CreationDate></Bucket>")
        }
        append("</Buckets></ListAllMyBucketsResult>")
    })
}

private suspend fun ApplicationCall.s3CreateBucket(token: WebdavTokenRecord, name: String) {
    if (name.lowercase() in S3_RESERVED_BUCKETS || !s3BucketNameOk(name)) {
        throw S3Exception("InvalidBucketName", HttpStatusCode.BadRequest, "桶名非法")
    }
    withContext(Dispatchers.IO) {
        transaction(DatabaseFactory.db) {
            val folder = FoldersTable.select(FoldersTable.id).where {
                (FoldersTable.user eq token.userId) and (FoldersTable.parent.isNull()) and (FoldersTable.name eq name)
            }.firstOrNull()
            if (folder != null) {
                throw S3Exception("BucketAlreadyOwnedByYou", HttpStatusCode.Conflict, "桶已存在")
            }
            val file = FilesTable.select(FilesTable.id).where {
                (FilesTable.user eq token.userId) and (FilesTable.folder.isNull()) and
                    (FilesTable.name eq name) and (FilesTable.deletedAt eq 0L)
            }.firstOrNull()
            if (file != null) {
                throw S3Exception("BucketAlreadyExists", HttpStatusCode.Conflict, "同名的是文件")
            }
            FoldersTable.insert {
                it[FoldersTable.id] = UUID.randomUUID(); it[FoldersTable.user] = token.userId
                it[FoldersTable.parent] = null; it[FoldersTable.name] = name
            }
        }
    }
    response.header(HttpHeaders.Location, "/$name")
    respondText("", ContentType.Application.Xml, HttpStatusCode.OK)
}

private suspend fun ApplicationCall.s3HeadBucket(token: WebdavTokenRecord, name: String) {
    s3RequireBucket(token.userId, name)
    response.header("x-amz-bucket-region", "us-east-1")
    respondText("", ContentType.Application.Xml, HttpStatusCode.OK)
}

private suspend fun ApplicationCall.s3DeleteBucket(token: WebdavTokenRecord, name: String) {
    val id = s3RequireBucket(token.userId, name)
    val storage = StorageRegistry.defaultProvider() ?: error("no storage backend registered")
    val keys = withContext(Dispatchers.IO) {
        transaction(DatabaseFactory.db) {
            val subtree = FileTree.subtreeWithRoot(token.userId, id)
            // 任何状态的文件（含回收站）都算非空；上传会话只算未完成的——已完成/
            // 已取消的行不再往目录里写东西，算进去会让刚 PUT 过的桶 24h 内删不掉。
            for (batch in subtree.chunked(500)) {
                val fileOp = SqlExpressionBuilder.run { FilesTable.folder inList batch }
                if (FilesTable.select(FilesTable.id).where { fileOp }.any()) {
                    throw S3Exception("BucketNotEmpty", HttpStatusCode.Conflict, "桶非空")
                }
                val sessionOp = SqlExpressionBuilder.run {
                    (UploadSessionsTable.folder inList batch) and (UploadSessionsTable.status eq "open")
                }
                if (UploadSessionsTable.select(UploadSessionsTable.id).where { sessionOp }.any()) {
                    throw S3Exception("BucketNotEmpty", HttpStatusCode.Conflict, "桶非空")
                }
            }
            if (subtree.size > 1) {
                throw S3Exception("BucketNotEmpty", HttpStatusCode.Conflict, "桶非空")
            }
            // 已完成的会话行仍按 FK 指着桶：走既有的级联删除，不在这里写第二份。
            FileService.deleteFolder(token.userId, id).blobKeys
        }
    }
    if (keys.isNotEmpty()) deleteStoredBlobs(storage, keys)
    respondText("", ContentType.Application.Xml, HttpStatusCode.NoContent)
}

private suspend fun ApplicationCall.s3BucketLocation(token: WebdavTokenRecord, name: String) {
    s3RequireBucket(token.userId, name)
    s3RespondXml("<LocationConstraint xmlns=\"$S3_NS\"/>")
}

private suspend fun ApplicationCall.s3BucketVersioning(token: WebdavTokenRecord, name: String) {
    s3RequireBucket(token.userId, name)
    s3RespondXml("<VersioningConfiguration xmlns=\"$S3_NS\"/>")
}

private suspend fun ApplicationCall.s3BucketUploads(token: WebdavTokenRecord, name: String) {
    s3RequireBucket(token.userId, name)
    s3RespondXml(
        "<ListMultipartUploadsResult xmlns=\"$S3_NS\"><Bucket>${DavProperties.xmlEscaped(name)}</Bucket>" +
            "<KeyMarker></KeyMarker><UploadIdMarker></UploadIdMarker><NextKeyMarker></NextKeyMarker>" +
            "<NextUploadIdMarker></NextUploadIdMarker><MaxUploads>1000</MaxUploads><IsTruncated>false</IsTruncated>" +
            "</ListMultipartUploadsResult>",
    )
}