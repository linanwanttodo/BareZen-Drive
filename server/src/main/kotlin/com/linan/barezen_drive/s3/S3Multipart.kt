package com.linan.barezen_drive.s3

import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.UploadChunksTable
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.files.MergePart
import com.linan.barezen_drive.files.UploadDigests
import com.linan.barezen_drive.files.UploadService
import com.linan.barezen_drive.storage.StorageRegistry
import com.linan.barezen_drive.webdav.DavProperties
import com.linan.barezen_drive.webdav.DavTarget
import com.linan.barezen_drive.webdav.WebdavTokenRecord
import com.linan.barezen_drive.webdav.davPutMimeType
import com.linan.barezen_drive.webdav.resolveDavPath
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

private const val MIN_PART = 5L * 1024 * 1024
private const val MAX_PART = 5L * 1024 * 1024 * 1024
private const val MAX_PARTS = 10000
private const val MPU_TTL = 24L * 3600 * 1000
private const val S3_NS = "http://s3.amazonaws.com/doc/2006-03-01/"

/** 只认 status=multipart、属主正确、未过期的会话；其余一律 NoSuchUpload。 */
private suspend fun ApplicationCall.openMpu(token: WebdavTokenRecord): ResultRow {
    val raw = request.queryParameters["uploadId"]
        ?: throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "缺少 uploadId")
    val id = runCatching { UUID.fromString(raw) }.getOrNull()
        ?: throw S3Exception("NoSuchUpload", HttpStatusCode.NotFound, "上传会话不存在")
    return withContext(Dispatchers.IO) {
        transaction(DatabaseFactory.db) {
            UploadSessionsTable.selectAll().where {
                (UploadSessionsTable.id eq id) and (UploadSessionsTable.user eq token.userId)
            }.singleOrNull()
        }
    }?.takeIf {
        it[UploadSessionsTable.status] == "multipart" && it[UploadSessionsTable.expiresAt] >= System.currentTimeMillis()
    } ?: throw S3Exception("NoSuchUpload", HttpStatusCode.NotFound, "上传会话不存在")
}

internal suspend fun ApplicationCall.s3CreateMultipart(token: WebdavTokenRecord) {
    val bucketName = s3BucketName()
    val bucketId = s3BucketId(token.userId, bucketName)
        ?: throw S3Exception("NoSuchBucket", HttpStatusCode.NotFound, "桶不存在")
    val segments = s3KeySegments(s3RawKey())
        ?: throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "键非法")
    val parent = s3EnsureParents(token.userId, bucketId, segments)
    val target = resolveDavPath(token.userId, listOf(bucketName) + segments)
    if (target is DavTarget.Collection) {
        throw S3Exception("InvalidRequest", HttpStatusCode.Conflict, "对象与文件夹不能同名")
    }
    val userMeta = s3UserMeta()
    val mime = davPutMimeType()
    val id = UUID.randomUUID()
    withContext(Dispatchers.IO) {
        transaction(DatabaseFactory.db) {
            UploadSessionsTable.insert {
                it[UploadSessionsTable.id] = id
                it[UploadSessionsTable.user] = token.userId
                it[UploadSessionsTable.folder] = parent
                it[UploadSessionsTable.name] = segments.last()
                it[UploadSessionsTable.size] = 0
                it[UploadSessionsTable.mimeType] = mime
                it[UploadSessionsTable.chunkSize] = UploadService.MAX_CHUNK
                it[UploadSessionsTable.clientSha256] = null
                it[UploadSessionsTable.userMeta] = userMeta
                it[UploadSessionsTable.overwrite] = true
                it[UploadSessionsTable.status] = "multipart"
                it[UploadSessionsTable.expiresAt] = System.currentTimeMillis() + MPU_TTL
            }
        }
    }
    s3RespondXml(
        "<InitiateMultipartUploadResult xmlns=\"$S3_NS\"><Bucket>${DavProperties.xmlEscaped(bucketName)}</Bucket>" +
            "<Key>${DavProperties.xmlEscaped(s3KeyOf(segments))}</Key><UploadId>$id</UploadId></InitiateMultipartUploadResult>",
    )
}

internal suspend fun ApplicationCall.s3UploadPart(token: WebdavTokenRecord) {
    val row = openMpu(token)
    val sid = row[UploadSessionsTable.id]
    val partNumber = request.queryParameters["partNumber"]?.toIntOrNull()
        ?: throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "partNumber 非法")
    if (partNumber !in 1..MAX_PARTS) {
        throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "partNumber 超出范围")
    }
    val declared = request.contentLength()
        ?: throw S3Exception("MissingContentLength", HttpStatusCode.LengthRequired, "缺少 Content-Length")
    if (declared < 0 || declared > MAX_PART) {
        throw S3Exception("EntityTooLarge", HttpStatusCode.BadRequest, "分片超过 5 GiB")
    }
    val checksums = s3Checksums()
    val contentMd5 = s3ContentMd5()
    val storage = StorageRegistry.defaultProvider() ?: error("no storage backend registered")
    val others = withContext(Dispatchers.IO) {
        transaction(DatabaseFactory.db) {
            UploadChunksTable.selectAll().where {
                (UploadChunksTable.session eq sid) and (UploadChunksTable.chunkIndex neq partNumber)
            }.sumOf { it[UploadChunksTable.size] }
        }
    }
    if (others + declared > UploadService.maxFileSize) {
        throw S3Exception("EntityTooLarge", HttpStatusCode.BadRequest, "累计超过服务器上限")
    }
    val dir = storage.tmpDir.resolve(sid.toString()).resolve("mpu").toFile().apply { mkdirs() }
    val staging = Files.createTempFile(dir.toPath(), "$partNumber-", ".part.tmp")
    val part = File(dir, "$partNumber.part")
    val digests = UploadDigests(checksums.keys + setOf("MD5"))
    var written = 0L
    try {
        val body = request.receiveChannel()
        withContext(Dispatchers.IO) {
            FileOutputStream(staging.toFile()).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = body.readAvailable(buf)
                    if (n < 0) break
                    if (n > 0) {
                        written += n
                        if (written > declared) throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "分片大小不符")
                        digests.update(buf, n)
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        if (written != declared) throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "分片大小不符")
        val raw = digests.raw()
        s3VerifyChecksums(raw, checksums)
        s3VerifyContentMd5(raw["MD5"]!!, contentMd5)
        val etag = raw["SHA256"]!!.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        // 核对过才改名：失败的分片留在临时名上，finally 里删掉，账本里那一号还是旧的。
        withContext(Dispatchers.IO) {
            Files.move(staging, part.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val algorithm = checksums.keys.firstOrNull()
        val encoded = algorithm?.let { a -> raw[a]?.let { Base64.getEncoder().encodeToString(it) } }
        withContext(Dispatchers.IO) {
            transaction(DatabaseFactory.db) {
                val updated = UploadChunksTable.update({
                    (UploadChunksTable.session eq sid) and (UploadChunksTable.chunkIndex eq partNumber)
                }) {
                    it[UploadChunksTable.size] = written
                    it[UploadChunksTable.etag] = etag
                    it[UploadChunksTable.checksum] = encoded
                    it[UploadChunksTable.checksumAlgorithm] = algorithm
                }
                if (updated == 0) {
                    UploadChunksTable.insert {
                        it[UploadChunksTable.session] = sid
                        it[UploadChunksTable.chunkIndex] = partNumber
                        it[UploadChunksTable.size] = written
                        it[UploadChunksTable.etag] = etag
                        it[UploadChunksTable.checksum] = encoded
                        it[UploadChunksTable.checksumAlgorithm] = algorithm
                    }
                }
                UploadSessionsTable.update({ UploadSessionsTable.id eq sid }) {
                    it[UploadSessionsTable.expiresAt] = System.currentTimeMillis() + MPU_TTL
                }
            }
        }
        response.header(HttpHeaders.ETag, "\"$etag\"")
        for (a in checksums.keys) {
            raw[a]?.let { response.header(s3ChecksumHeader(a), Base64.getEncoder().encodeToString(it)) }
        }
        respond(HttpStatusCode.OK)
    } finally {
        runCatching { Files.deleteIfExists(staging) }
    }
}

internal suspend fun ApplicationCall.s3CompleteMultipart(token: WebdavTokenRecord) {
    val row = openMpu(token)
    val sid = row[UploadSessionsTable.id]
    val root = readS3Xml()
    if (root.localName != "CompleteMultipartUpload" && root.nodeName != "CompleteMultipartUpload") {
        throw S3Exception("MalformedXML", HttpStatusCode.BadRequest, "根元素必须是 CompleteMultipartUpload")
    }
    val requested = root.childElements().filter { it.localName == "Part" || it.nodeName == "Part" }.map { p ->
        val n = p.childText("PartNumber")?.trim()?.toIntOrNull()
            ?: throw S3Exception("InvalidPart", HttpStatusCode.BadRequest, "PartNumber 非法")
        val etag = p.childText("ETag")?.trim()?.trim('"')
            ?: throw S3Exception("InvalidPart", HttpStatusCode.BadRequest, "缺少 ETag")
        n to etag
    }
    if (requested.isEmpty()) throw S3Exception("MalformedXML", HttpStatusCode.BadRequest, "至少一个 Part")
    for (i in 1 until requested.size) {
        if (requested[i].first <= requested[i - 1].first) {
            throw S3Exception("InvalidPartOrder", HttpStatusCode.BadRequest, "Part 必须严格升序且无重复")
        }
    }
    val ledger = withContext(Dispatchers.IO) {
        transaction(DatabaseFactory.db) {
            UploadChunksTable.selectAll().where { UploadChunksTable.session eq sid }
                .associateBy { it[UploadChunksTable.chunkIndex] }
        }
    }
    val storage = StorageRegistry.defaultProvider() ?: error("no storage backend registered")
    val dir = storage.tmpDir.resolve(sid.toString()).resolve("mpu")
    val parts = ArrayList<MergePart>(requested.size)
    var total = 0L
    requested.forEachIndexed { i, (n, etag) ->
        val recorded = ledger[n] ?: throw S3Exception("InvalidPart", HttpStatusCode.BadRequest, "Part $n 不存在")
        if (!recorded[UploadChunksTable.etag].equals(etag, ignoreCase = true)) {
            throw S3Exception("InvalidPart", HttpStatusCode.BadRequest, "Part $n 的 ETag 不符")
        }
        val file = dir.resolve("$n.part").toFile()
        // 自己先看一眼：合并那段会把缺失的分片报成 InvalidArgument，而 S3 在这里是 InvalidPart。
        if (!file.exists()) throw S3Exception("InvalidPart", HttpStatusCode.BadRequest, "Part $n 的暂存已不在")
        val size = recorded[UploadChunksTable.size]
        if (i < requested.lastIndex && size < MIN_PART) {
            throw S3Exception("EntityTooSmall", HttpStatusCode.BadRequest, "除最后一片外每片至少 5 MiB")
        }
        total += size
        parts += MergePart(file, size, etag)
    }
    if (total > UploadService.maxFileSize) {
        throw S3Exception("EntityTooLarge", HttpStatusCode.BadRequest, "文件超过服务器上限")
    }
    val completeChecksums = s3Checksums()
    for ((algorithm, want) in completeChecksums) {
        val raws = requested.map { (n, _) ->
            val r = ledger[n]!!
            if (r[UploadChunksTable.checksumAlgorithm] != algorithm) null
            else r[UploadChunksTable.checksum]?.let { Base64.getDecoder().decode(it) }
        }
        if (raws.all { it != null }) {
            val composite = s3CompositeChecksum(algorithm, raws.filterNotNull())
            if (!MessageDigest.isEqual(composite.toByteArray(), want.trim().toByteArray())) {
                throw S3Exception("BadDigest", HttpStatusCode.BadRequest, "复合校验和不符")
            }
        }
    }
    val merged = UploadService.publishMerged(token.userId, row, total, parts, storage, snapshotOnOverwrite = false)
    val bucketName = s3BucketName()
    val key = s3KeyOf(s3KeySegments(s3RawKey())!!)
    response.header(HttpHeaders.Location, "/$bucketName/$key")
    s3RespondXml(
        "<CompleteMultipartUploadResult xmlns=\"$S3_NS\"><Location>/$bucketName/$key</Location>" +
            "<Bucket>${DavProperties.xmlEscaped(bucketName)}</Bucket><Key>${DavProperties.xmlEscaped(key)}</Key>" +
            "<ETag>&quot;${merged.sha256}&quot;</ETag></CompleteMultipartUploadResult>",
    )
}

internal suspend fun ApplicationCall.s3AbortMultipart(token: WebdavTokenRecord) {
    val row = openMpu(token)
    val sid = row[UploadSessionsTable.id]
    val storage = StorageRegistry.defaultProvider() ?: error("no storage backend registered")
    withContext(Dispatchers.IO) {
        transaction(DatabaseFactory.db) {
            UploadChunksTable.deleteWhere { UploadChunksTable.session eq sid }
            UploadSessionsTable.deleteWhere { UploadSessionsTable.id eq sid }
        }
        UploadService.cleanupSessionDir(storage, sid)
    }
    respond(HttpStatusCode.NoContent)
}
