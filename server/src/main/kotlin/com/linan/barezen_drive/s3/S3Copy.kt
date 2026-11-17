package com.linan.barezen_drive.s3

import com.linan.barezen_drive.api.readBounded
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.files.FileService
import com.linan.barezen_drive.files.deleteStoredBlobs
import com.linan.barezen_drive.storage.StorageRegistry
import com.linan.barezen_drive.webdav.DavProperties
import com.linan.barezen_drive.webdav.DavTarget
import com.linan.barezen_drive.webdav.WebdavTokenRecord
import com.linan.barezen_drive.webdav.davHttpDateOrNull
import com.linan.barezen_drive.webdav.davWholeSeconds
import com.linan.barezen_drive.webdav.percentDecode
import com.linan.barezen_drive.webdav.resolveDavPath
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

private const val S3_NS = "http://s3.amazonaws.com/doc/2006-03-01/"

internal suspend fun ApplicationCall.s3CopyObject(token: WebdavTokenRecord) {
    val rawSource = request.headers["x-amz-copy-source"]!!
    if (rawSource.substringAfter('?', "").isNotEmpty()) {
        throw S3Exception("NotImplemented", HttpStatusCode.NotImplemented, "不支持带 versionId 的复制源")
    }
    val sourcePath = rawSource.removePrefix("/")
    val srcSegments = sourcePath.split('/', limit = 2)
    if (srcSegments.size != 2) throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "x-amz-copy-source 格式非法")
    val srcBucket = s3PercentDecode(srcSegments[0])
    val srcKey = s3KeySegments(srcSegments[1])
        ?: throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "源键非法")
    if (s3BucketId(token.userId, srcBucket) == null) {
        throw S3Exception("NoSuchBucket", HttpStatusCode.NotFound, "源桶不存在")
    }
    val srcTarget = resolveDavPath(token.userId, listOf(srcBucket) + srcKey)
    val srcId = (srcTarget as? DavTarget.Blob)?.id
        ?: throw S3Exception("NoSuchKey", HttpStatusCode.NotFound, "源键不存在")
    val source = s3ObjectRow(token.userId, srcId)
        ?: throw S3Exception("NoSuchKey", HttpStatusCode.NotFound, "源键不存在")

    val sourceEtag = "\"${source.meta.sha256}\""
    copySourceCondition("x-amz-copy-source-if-match", sourceEtag, strong = true)?.let { throw it }
    copySourceCondition("x-amz-copy-source-if-none-match", sourceEtag, strong = false)?.let { throw it }
    copySourceSince("x-amz-copy-source-if-modified-since", source.updatedAt, modified = true)?.let { throw it }
    copySourceSince("x-amz-copy-source-if-unmodified-since", source.updatedAt, modified = false)?.let { throw it }

    val dstBucket = s3BucketName()
    val dstBucketId = s3BucketId(token.userId, dstBucket)
        ?: throw S3Exception("NoSuchBucket", HttpStatusCode.NotFound, "桶不存在")
    val dstSegments = s3KeySegments(s3RawKey())
        ?: throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "键非法")
    val directive = request.headers["x-amz-metadata-directive"]?.uppercase() ?: "COPY"
    if (directive != "COPY" && directive != "REPLACE") {
        throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "x-amz-metadata-directive 非法")
    }
    val samePath = srcBucket == dstBucket && srcKey == dstSegments
    if (samePath && directive == "COPY") {
        throw S3Exception("InvalidRequest", HttpStatusCode.BadRequest, "源与目标相同且未替换元数据")
    }
    val dstMeta = if (directive == "REPLACE") s3UserMeta() else source.userMeta
    val parent = s3EnsureParents(token.userId, dstBucketId, dstSegments)
    val dstTarget = resolveDavPath(token.userId, listOf(dstBucket) + dstSegments)
    if (dstTarget is DavTarget.Collection) {
        throw S3Exception("InvalidRequest", HttpStatusCode.Conflict, "对象与文件夹不能同名")
    }
    val dstBlobId = (dstTarget as? DavTarget.Blob)?.id
    val storage = StorageRegistry.defaultProvider() ?: error("no storage backend registered")
    // 一次事务内的"删旧 + 插新"：目标不会被删了一半。孤儿键提交后才进删除队列。
    val (newId, orphans) = withContext(Dispatchers.IO) {
        transaction(DatabaseFactory.db) {
            if (dstBlobId == srcId) {
                // 源就是目标：删了它 copyFileRow 就没有源可读了，而且它是该 blob 的
                // 唯一引用，orphanBlobKeys 会把字节排进删除队列。原地改元数据即可。
                FilesTable.update({ FilesTable.id eq srcId }) {
                    it[FilesTable.userMeta] = dstMeta
                    it[FilesTable.updatedAt] = System.currentTimeMillis()
                }
                return@transaction srcId to emptyList<String>()
            }
            val oldKey = if (dstBlobId != null) {
                FilesTable.select(FilesTable.storageKey).where { FilesTable.id eq dstBlobId }.firstOrNull()?.get(FilesTable.storageKey)
            } else null
            val removed = if (oldKey != null) FileService.hardDelete(listOf(dstBlobId!! to oldKey)) else emptyList()
            val id = FileService.copyFileRow(token.userId, srcId, parent, dstSegments.last()) { dstMeta }
            id to removed
        }
    }
    if (orphans.isNotEmpty()) deleteStoredBlobs(storage, orphans)
    val created = s3ObjectRow(token.userId, newId)!!
    s3RespondXml(
        "<CopyObjectResult xmlns=\"$S3_NS\">" +
            "<ETag>&quot;${created.meta.sha256}&quot;</ETag>" +
            "<LastModified>${s3Timestamp(created.updatedAt)}</LastModified></CopyObjectResult>",
    )
}

/** 返回 null 表示条件成立；否则返回要抛的 412。 */
private fun ApplicationCall.copySourceCondition(header: String, etag: String, strong: Boolean): S3Exception? {
    val value = request.headers[header] ?: return null
    val tags = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val matches = tags.contains("*") || tags.any { it.removePrefix("W/") == etag }
    val failed = if (strong) !matches else matches
    return if (failed) S3Exception("PreconditionFailed", HttpStatusCode.PreconditionFailed, "源对象条件不符") else null
}

private fun ApplicationCall.copySourceSince(header: String, updatedAt: Long, modified: Boolean): S3Exception? {
    val value = request.headers[header] ?: return null
    val since = davHttpDateOrNull(value) ?: return null
    val whole = updatedAt.davWholeSeconds()
    val failed = if (modified) whole <= since.davWholeSeconds() else whole > since.davWholeSeconds()
    return if (failed) S3Exception("PreconditionFailed", HttpStatusCode.PreconditionFailed, "源对象条件不符") else null
}

/** 单段的百分号解码（桶名用）；复用 WebDAV 的硬化解码。 */
private fun s3PercentDecode(s: String): String =
    percentDecode(s) ?: throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "桶名编码非法")

internal suspend fun ApplicationCall.s3DeleteObjects(token: WebdavTokenRecord) {
    val body = readBounded(request.receiveChannel(), S3_XML_LIMIT.toLong(), request.contentLength() ?: 0)
        ?: throw S3Exception("InvalidRequest", HttpStatusCode.BadRequest, "请求体过大")
    val md5Header = request.headers["Content-MD5"]
        ?: throw S3Exception("InvalidRequest", HttpStatusCode.BadRequest, "DeleteObjects 必须带 Content-MD5")
    val actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body))
    if (!MessageDigest.isEqual(actual.toByteArray(), md5Header.trim().toByteArray())) {
        throw S3Exception("InvalidDigest", HttpStatusCode.BadRequest, "Content-MD5 不符")
    }
    val root = parseS3XmlBytes(body)
    if (root.localName != "Delete" && root.nodeName != "Delete") {
        throw S3Exception("MalformedXML", HttpStatusCode.BadRequest, "根元素必须是 Delete")
    }
    val quiet = root.childText("Quiet")?.equals("true", ignoreCase = true) == true
    val keys = root.childElements().filter { it.localName == "Object" || it.nodeName == "Object" }.map { obj ->
        obj.childText("Key") ?: throw S3Exception("MalformedXML", HttpStatusCode.BadRequest, "Object 缺少 Key")
    }
    if (keys.size > 1000) throw S3Exception("MalformedXML", HttpStatusCode.BadRequest, "一次最多 1000 个对象")

    val bucketName = s3BucketName()
    val bucketId = s3BucketId(token.userId, bucketName)
        ?: throw S3Exception("NoSuchBucket", HttpStatusCode.NotFound, "桶不存在")
    val doomed = ArrayList<Pair<UUID, String>>()
    val parents = HashSet<UUID>()
    for (key in keys) {
        val segments = s3KeySegments(key) ?: continue
        when (val target = resolveDavPath(token.userId, listOf(bucketName) + segments)) {
            is DavTarget.Blob -> {
                val row = s3ObjectRow(token.userId, target.id) ?: continue
                doomed += row.meta.id to row.meta.storageKey
                row.meta.folderId?.let { parents += it }
            }
            else -> Unit
        }
    }
    val storage = StorageRegistry.defaultProvider() ?: error("no storage backend registered")
    val orphans = withContext(Dispatchers.IO) {
        transaction(DatabaseFactory.db) { FileService.hardDelete(doomed) }
    }
    if (orphans.isNotEmpty()) deleteStoredBlobs(storage, orphans)
    for (parent in parents) s3PruneEmptyAncestors(token.userId, parent, bucketId)
    s3RespondXml(buildString {
        append("<DeleteResult xmlns=\"$S3_NS\">")
        if (!quiet) keys.forEach { append("<Deleted><Key>").append(DavProperties.xmlEscaped(it)).append("</Key></Deleted>") }
        append("</DeleteResult>")
    })
}