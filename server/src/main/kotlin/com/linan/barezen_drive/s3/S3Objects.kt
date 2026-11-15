package com.linan.barezen_drive.s3

import com.linan.barezen_drive.core.dto.UploadInitRequest
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.files.FileMeta
import com.linan.barezen_drive.files.FileService
import com.linan.barezen_drive.files.UploadDigests
import com.linan.barezen_drive.files.UploadService
import com.linan.barezen_drive.files.deleteStoredBlobs
import com.linan.barezen_drive.files.respondStoredFile
import com.linan.barezen_drive.storage.SigV4
import com.linan.barezen_drive.storage.StorageRegistry
import com.linan.barezen_drive.webdav.DavPrecondition
import com.linan.barezen_drive.webdav.DavProperties
import com.linan.barezen_drive.webdav.DavTarget
import com.linan.barezen_drive.webdav.WebdavTokenRecord
import com.linan.barezen_drive.webdav.davPrecondition
import com.linan.barezen_drive.webdav.davPutMimeType
import com.linan.barezen_drive.webdav.davStreamChunks
import com.linan.barezen_drive.webdav.resolveDavPath
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ClosedByteChannelException
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
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
        "PUT" -> s3PutObject(token)
        "DELETE" -> s3DeleteObject(token)
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
    // HEAD 也走 respondStoredFile：路由上的 AutoHeadBody 只换掉 body，状态码、
    // Content-Length 与 416/404 的判定都留给同一条路径，手写一份必然漂移。
    respondStoredFile(storage, row.meta.copy(storageKey = key))
}

internal suspend fun ApplicationCall.s3PutObject(token: WebdavTokenRecord) {
    s3Writable(token)
    if (request.headers["x-amz-copy-source"] != null) {
        // Task 15 把这一行换成 s3CopyObject(token)。
        throw S3Exception("NotImplemented", HttpStatusCode.NotImplemented, "CopyObject 尚未实现")
    }
    val bucketName = s3BucketName()
    val bucketId = s3BucketId(token.userId, bucketName)
        ?: throw S3Exception("NoSuchBucket", HttpStatusCode.NotFound, "桶不存在")
    val segments = s3KeySegments(s3RawKey())
        ?: throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "键非法")
    val name = segments.last()
    val payloadHeader = request.headers["x-amz-content-sha256"]
    if (payloadHeader != null && payloadHeader.startsWith("STREAMING-AWS4-ECDSA")) {
        throw S3Exception("NotImplemented", HttpStatusCode.NotImplemented, "不支持 SigV4A")
    }
    val streaming = payloadHeader != null && payloadHeader.startsWith("STREAMING-")
    val declared = if (streaming) {
        request.headers["x-amz-decoded-content-length"]?.toLongOrNull()
    } else {
        request.contentLength()
    }
    // 长度判定在建目录之前：一个注定被拒的请求不该先在用户的树里留下空目录。
    if (declared == null || declared < 0) {
        throw S3Exception("MissingContentLength", HttpStatusCode.LengthRequired, "缺少 Content-Length 或 x-amz-decoded-content-length")
    }
    if (declared > UploadService.maxFileSize) {
        throw S3Exception("EntityTooLarge", HttpStatusCode.BadRequest, "文件超过服务器上限")
    }
    val parent = s3EnsureParents(token.userId, bucketId, segments)
    val target = resolveDavPath(token.userId, listOf(bucketName) + segments)
    if (target is DavTarget.Collection) {
        throw S3Exception("InvalidRequest", HttpStatusCode.Conflict, "对象与文件夹不能同名")
    }
    val userMeta = s3UserMeta()
    val checksums = s3Checksums()
    val contentMd5 = s3ContentMd5()
    val hexSha = payloadHeader?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
    // 目标已存在时不给 sha256：秒传分支会在核对校验和之前就落行，而覆盖场景下
    // 落行后再失败会丢旧内容。新名字才允许秒传（失败时删掉本次新建的行即可）。
    val clientSha = if (target is DavTarget.Blob) null else hexSha

    val expectTrailer = streaming && payloadHeader!!.endsWith("-TRAILER")
    val storage = StorageRegistry.defaultProvider() ?: error("no storage backend registered")
    var body = request.receiveChannel()
    var chunked: AwsChunkedBody? = null
    if (streaming) {
        val ctx = attributes.getOrNull(s3SigningContextKey)
            ?: throw S3Exception("AccessDenied", HttpStatusCode.Forbidden, "缺少签名上下文")
        val unsigned = payloadHeader == "STREAMING-UNSIGNED-PAYLOAD-TRAILER"
        chunked = awsChunked(
            body,
            AwsChunkedOptions(
                decodedLength = declared,
                signingKey = if (unsigned) null else SigV4.signingKey(ctx.secret, ctx.dateStamp, ctx.region, "s3"),
                amzDate = ctx.amzDate,
                scope = "${ctx.dateStamp}/${ctx.region}/s3/aws4_request",
                seedSignature = ctx.seedSignature,
                expectTrailer = expectTrailer,
            ),
        )
        body = chunked.channel
    }

    val digests = UploadDigests(
        checksums.keys + setOf("MD5") + if (expectTrailer) trailerAlgorithms() else emptySet(),
    )
    val init = withContext(Dispatchers.IO) {
        UploadService.initUpload(
            token.userId,
            UploadInitRequest(
                folderId = parent.toString(), name = name, size = declared,
                mimeType = davPutMimeType(), sha256 = clientSha,
                chunkSize = UploadService.MAX_CHUNK, overwrite = true, userMeta = userMeta,
            ),
            storage, allowResume = false, snapshotOnOverwrite = false,
        )
    }
    // 秒传没有会话，uploadId 是空串。
    val sid = if (init.instantUpload) null else UUID.fromString(init.uploadId)
    try {
        // 秒传要求 64 位 hex 载荷哈希，与 STREAMING-* 互斥，所以这一支没有解帧器要收尾。
        if (init.instantUpload) {
            drainDigesting(body, declared, digests)
            val raw = digests.raw()
            val all = checksums + s3TrailerChecksums(chunked?.trailers.orEmpty())
            verifyPayload(raw, hexSha, all, contentMd5)
            echoChecksums(raw, all)
            response.header(HttpHeaders.ETag, "\"${init.file!!.sha256}\"")
            respond(HttpStatusCode.OK)
            return
        }
        davStreamChunks(token.userId, sid!!, body, declared, init.chunkSize, storage)
        decoderFailure(chunked)?.let { throw it }
        // trailer 要读完 body 才知道，所以在这里并进校验和集合，再交给合并循环核对。
        val all = checksums + s3TrailerChecksums(chunked?.trailers.orEmpty())
        val merged = UploadService.completeWithDigests(token.userId, sid, storage, false, digests) { raw ->
            verifyPayload(raw, hexSha, all, contentMd5)
        }
        echoChecksums(merged.digests, all)
        response.header(HttpHeaders.ETag, "\"${merged.sha256}\"")
        respond(HttpStatusCode.OK)
    } catch (t: Throwable) {
        // 新名字的秒传已经落了行：核对失败就把它删掉（它共享的是已存在的 blob，
        // orphanBlobKeys 不会误删字节）。目标事先是 Missing，所以这一行一定是
        // 本次新建的，删它不会丢任何旧内容。
        if (init.instantUpload && target == DavTarget.Missing) {
            runCatching {
                val id = UUID.fromString(init.file!!.id)
                val keys = withContext(Dispatchers.IO) {
                    transaction(DatabaseFactory.db) { FileService.hardDelete(listOf(id to storage.blobKey(init.file!!.sha256))) }
                }
                if (keys.isNotEmpty()) deleteStoredBlobs(storage, keys)
            }
        } else if (!init.instantUpload) {
            runCatching { withContext(Dispatchers.IO) { UploadService.abort(token.userId, sid!!, storage) } }
        }
        if (t is CancellationException) throw t
        throw decoderFailure(chunked) ?: t
    }
}

private fun verifyPayload(
    raw: Map<String, ByteArray>, hexSha: String?, checksums: Map<String, String>, contentMd5: String?,
) {
    if (hexSha != null) {
        val sha = raw["SHA256"]!!.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        if (!hexSha.equals(sha, ignoreCase = true)) {
            throw S3Exception("XAmzContentSHA256Mismatch", HttpStatusCode.BadRequest, "载荷哈希不符")
        }
    }
    s3VerifyChecksums(raw, checksums)
    s3VerifyContentMd5(raw["MD5"]!!, contentMd5)
}

private fun ApplicationCall.echoChecksums(raw: Map<String, ByteArray>, requested: Map<String, String>) {
    for (algorithm in requested.keys) {
        raw[algorithm]?.let { response.header(s3ChecksumHeader(algorithm), Base64.getEncoder().encodeToString(it)) }
    }
}

/** aws-chunked 的 trailer 里带的校验和，翻成算法名 → base64。 */
private fun s3TrailerChecksums(trailers: Map<String, String>): Map<String, String> =
    trailers.mapNotNull { (k, v) -> s3ChecksumAlgorithm(k)?.let { it to v } }.toMap()

/**
 * trailer 会带哪些校验和。摘要必须在读 body 之前就备好，而 trailer 的内容读完
 * 才知道，所以只能靠 x-amz-trailer 的声明；没声明就四种都算，免得客户端送了
 * trailer 却因为没准备摘要而被误拒。
 */
private fun ApplicationCall.trailerAlgorithms(): Set<String> {
    val declared = request.headers["x-amz-trailer"]
        ?.split(',')?.mapNotNull { s3ChecksumAlgorithm(it.trim()) }?.toSet()
    return if (declared.isNullOrEmpty()) S3_DIGEST_ALGORITHMS - "MD5" else declared
}

/** 按声明长度硬停地读完并丢弃，同时喂摘要。不允许无限读。 */
private suspend fun drainDigesting(body: ByteReadChannel, declared: Long, digests: UploadDigests) {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (total < declared) {
        val n = body.readAvailable(buf, 0, minOf(declared - total, buf.size.toLong()).toInt())
        if (n <= 0) break
        digests.update(buf, n)
        total += n
    }
}

/**
 * 读到 EOF 才算解帧结束：trailer 在这之后才有值，而"数据已全部交给下游之后才
 * 校验"的块签名错误也只有在这里才暴露得出来。返回解帧器自己的失败原因——它比
 * 下游的"短块"更准确。
 */
private suspend fun decoderFailure(chunked: AwsChunkedBody?): Throwable? {
    if (chunked == null) return null
    return runCatching { chunked.channel.readRemaining() }.exceptionOrNull()?.unwrapChannelCause()
}

internal suspend fun ApplicationCall.s3DeleteObject(token: WebdavTokenRecord) {
    s3Writable(token)
    val bucketName = s3BucketName()
    val bucketId = s3BucketId(token.userId, bucketName)
        ?: throw S3Exception("NoSuchBucket", HttpStatusCode.NotFound, "桶不存在")
    val segments = s3KeySegments(s3RawKey())
        ?: throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "键非法")
    val target = resolveDavPath(token.userId, listOf(bucketName) + segments)
    val storage = StorageRegistry.defaultProvider() ?: error("no storage backend registered")
    if (target is DavTarget.Blob) {
        val row = s3ObjectRow(token.userId, target.id)
        if (row != null) {
            val keys = withContext(Dispatchers.IO) {
                transaction(DatabaseFactory.db) { FileService.hardDelete(listOf(row.meta.id to row.meta.storageKey)) }
            }
            if (keys.isNotEmpty()) deleteStoredBlobs(storage, keys)
        }
    }
    // 指向文件夹的 key、不存在的 key 都 204（S3 的幂等语义）
    val parent = s3ParentOf(token.userId, bucketId, segments)
    s3PruneEmptyAncestors(token.userId, parent, bucketId)
    respond(HttpStatusCode.NoContent)
}

/** 键的父目录 id；解析不到就返回桶 id（剪枝从桶开始等于不剪）。 */
private suspend fun s3ParentOf(userId: UUID, bucketId: UUID, segments: List<String>): UUID {
    var current = bucketId
    withContext(Dispatchers.IO) {
        for (i in 0 until segments.size - 1) {
            current = transaction(DatabaseFactory.db) {
                FoldersTable.select(FoldersTable.id).where {
                    (FoldersTable.user eq userId) and (FoldersTable.parent eq current) and (FoldersTable.name eq segments[i])
                }.firstOrNull()?.get(FoldersTable.id)
            } ?: return@withContext
        }
    }
    return current
}