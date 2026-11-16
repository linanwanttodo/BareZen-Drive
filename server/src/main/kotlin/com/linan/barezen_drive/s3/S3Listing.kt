package com.linan.barezen_drive.s3

import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.storage.SigV4
import com.linan.barezen_drive.webdav.DavEntry
import com.linan.barezen_drive.webdav.DavListing
import com.linan.barezen_drive.webdav.DavProperties
import com.linan.barezen_drive.webdav.WebdavTokenRecord
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Base64
import java.util.UUID

internal object S3Listing {
    fun encodeToken(key: String, id: UUID): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("v1\n$key\n$id".toByteArray(Charsets.UTF_8))

    fun decodeToken(raw: String): Pair<String, UUID>? = runCatching {
        val text = String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)
        val parts = text.split('\n')
        if (parts.size != 3 || parts[0] != "v1" || parts[1].isEmpty()) return null
        parts[1] to UUID.fromString(parts[2])
    }.getOrNull()
}

private const val S3_NS = "http://s3.amazonaws.com/doc/2006-03-01/"
private const val MAX_KEYS_LIMIT = 1000

/** 一个 DFS 帧：当前目录、它的键前缀、本层游标、以及手上这一页。 */
private class Frame(
    val dirId: UUID,
    val path: String,
    var afterName: String?,
    var afterId: UUID?,
) {
    var page: List<DavEntry> = emptyList()
    var index: Int = 0
}

private sealed interface Item {
    val key: String
    val id: UUID

    data class Obj(override val key: String, override val id: UUID, val entry: DavEntry) : Item
    data class Prefix(override val key: String, override val id: UUID) : Item
}

internal suspend fun ApplicationCall.s3ListObjects(token: WebdavTokenRecord) {
    val bucketName = s3BucketName()
    val bucketId = s3BucketId(token.userId, bucketName)
        ?: throw S3Exception("NoSuchBucket", HttpStatusCode.NotFound, "桶不存在")
    val params = request.queryParameters
    // V1 与 V2 的差别只在分页参数与响应字段，遍历逻辑完全共用。rclone 在
    // provider=Other 时按 auto 发 V1，所以两条都要能走通。
    val v2 = when (params["list-type"]) {
        "2" -> true
        null -> false
        else -> throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "list-type 非法")
    }
    val prefix = params["prefix"] ?: ""
    val delimiter = params["delimiter"] ?: ""
    if (delimiter != "" && delimiter != "/") {
        throw S3Exception("NotImplemented", HttpStatusCode.NotImplemented, "只支持空或 / 分隔符")
    }
    val maxKeysRaw = when (val raw = params["max-keys"]) {
        null -> MAX_KEYS_LIMIT
        else -> raw.toIntOrNull()
            ?: throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "max-keys 非法")
    }
    if (maxKeysRaw < 0) throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "max-keys 非法")
    val maxKeys = minOf(maxKeysRaw, MAX_KEYS_LIMIT)
    val encodingUrl = params["encoding-type"] == "url"
    val startAfter = if (v2) params["start-after"] else null
    val continuation = if (v2) params["continuation-token"] else null
    val marker = if (v2) null else params["marker"]?.takeIf { it.isNotEmpty() }

    // prefix 切分：去掉末尾空段后逐段解析到起始目录；不以 / 结尾时最后一段是名字前缀
    val endsWithSlash = prefix.endsWith("/")
    val segs = prefix.split('/').filter { it.isNotEmpty() }
    val dirSegs = if (endsWithSlash) segs else segs.dropLast(1)
    val namePrefix = if (endsWithSlash) "" else segs.lastOrNull().orEmpty()

    val startDir = withContext(Dispatchers.IO) { resolveDir(token.userId, bucketId, dirSegs) }
    val startPath = if (dirSegs.isEmpty()) "" else dirSegs.joinToString("/") + "/"

    val items = ArrayList<Item>(maxKeys + 1)
    if (maxKeys > 0 && startDir != null) {
        val stack = initialStack(token.userId, startDir, startPath, continuation, startAfter ?: marker)
        if (stack != null) {
            val walker = Walker(token.userId, stack, startPath, namePrefix, delimiter, maxKeys + 1)
            while (items.size < maxKeys + 1) {
                val item = walker.next() ?: break
                items += item
            }
        }
    }
    val truncated = items.size > maxKeys
    val page = items.take(maxKeys)
    val nextToken = if (truncated && page.isNotEmpty()) S3Listing.encodeToken(page.last().key, page.last().id) else null

    // encoding-type=url 时连 '/' 也编码（与 S3 一致）；客户端会整体百分号解码。
    fun enc(s: String): String =
        DavProperties.xmlEscaped(if (encodingUrl) SigV4.uriEncode(s, encodeSlash = true) else s)

    s3RespondXml(buildString {
        append("<ListBucketResult xmlns=\"").append(S3_NS).append("\">")
        append("<Name>").append(enc(bucketName)).append("</Name>")
        append("<Prefix>").append(enc(prefix)).append("</Prefix>")
        if (v2) append("<KeyCount>").append(page.size).append("</KeyCount>")
        else append("<Marker>").append(enc(marker.orEmpty())).append("</Marker>")
        // V1 只靠 NextMarker 翻页（没有 opaque token），截断时必须给出去。
        if (!v2 && truncated && page.isNotEmpty()) {
            append("<NextMarker>").append(enc(page.last().key)).append("</NextMarker>")
        }
        append("<MaxKeys>").append(maxKeys).append("</MaxKeys>")
        if (delimiter.isNotEmpty()) append("<Delimiter>").append(enc(delimiter)).append("</Delimiter>")
        append("<IsTruncated>").append(truncated).append("</IsTruncated>")
        if (v2) {
            if (continuation != null) append("<ContinuationToken>").append(DavProperties.xmlEscaped(continuation)).append("</ContinuationToken>")
            if (nextToken != null) append("<NextContinuationToken>").append(DavProperties.xmlEscaped(nextToken)).append("</NextContinuationToken>")
            if (startAfter != null) append("<StartAfter>").append(enc(startAfter)).append("</StartAfter>")
        }
        for (item in page) {
            when (item) {
                is Item.Obj -> {
                    append("<Contents><Key>").append(enc(item.key)).append("</Key>")
                    append("<LastModified>").append(s3Timestamp(item.entry.updatedAt)).append("</LastModified>")
                    append("<ETag>&quot;").append(item.entry.etag).append("&quot;</ETag>")
                    append("<Size>").append(item.entry.size).append("</Size>")
                    append("<StorageClass>STANDARD</StorageClass></Contents>")
                }
                is Item.Prefix -> append("<CommonPrefixes><Prefix>").append(enc(item.key)).append("</Prefix></CommonPrefixes>")
            }
        }
        append("</ListBucketResult>")
    })
}

/** 从桶根逐段解析目录；任何一段不存在或是文件就返回 null（调用方给空页）。 */
private fun resolveDir(userId: UUID, bucketId: UUID, segs: List<String>): UUID? =
    transaction(DatabaseFactory.db) {
        var current = bucketId
        for (seg in segs) {
            current = FoldersTable.select(FoldersTable.id).where {
                (FoldersTable.user eq userId) and (FoldersTable.parent eq current) and (FoldersTable.name eq seg)
            }.firstOrNull()?.get(FoldersTable.id) ?: return@transaction null
        }
        current
    }

/** 某一层里按名字找条目（目录优先，其次存活文件），用于恢复游标。 */
private fun lookupEntry(userId: UUID, dir: UUID, name: String): DavEntry? =
    transaction(DatabaseFactory.db) {
        FoldersTable.select(FoldersTable.id, FoldersTable.name, FoldersTable.createdAt, FoldersTable.updatedAt)
            .where { (FoldersTable.user eq userId) and (FoldersTable.parent eq dir) and (FoldersTable.name eq name) }
            .firstOrNull()?.let {
                DavEntry(it[FoldersTable.id], it[FoldersTable.name], true, 0L, it[FoldersTable.updatedAt], it[FoldersTable.createdAt], null)
            }
            ?: FilesTable.select(FilesTable.id, FilesTable.name, FilesTable.size, FilesTable.createdAt, FilesTable.updatedAt, FilesTable.sha256)
                .where { (FilesTable.user eq userId) and (FilesTable.folder eq dir) and (FilesTable.name eq name) and (FilesTable.deletedAt eq 0L) }
                .firstOrNull()?.let {
                    DavEntry(it[FilesTable.id], it[FilesTable.name], false, it[FilesTable.size], it[FilesTable.updatedAt], it[FilesTable.createdAt], it[FilesTable.sha256])
                }
    }

/**
 * 恢复游标：从 prefix 的起始目录逐段解析回键所在的那一层，每层建一个"停在该条目
 * 之后"的帧。任何一段不存在就返回 null（空页）——与 spec §9.3 一致。
 */
private suspend fun initialStack(
    userId: UUID,
    startDir: UUID,
    startPath: String,
    continuation: String?,
    startAfter: String?,
): MutableList<Frame>? = withContext(Dispatchers.IO) {
    val fromToken = continuation != null
    val cursorKey = when {
        fromToken -> S3Listing.decodeToken(continuation!!)?.first
            ?: throw S3Exception("InvalidArgument", HttpStatusCode.BadRequest, "continuation-token 非法")
        startAfter != null -> startAfter
        else -> return@withContext arrayListOf(Frame(startDir, startPath, null, null))
    }
    // 帧必须从 prefix 的起始目录建起，不能从桶根：子树耗尽后栈会弹回上一层，
    // 从桶根建就会继续列出 prefix 之外的兄弟条目，分页拼接凭空多出键。
    if (!cursorKey.startsWith(startPath)) {
        return@withContext if (cursorKey.lowercase() < startPath.lowercase()) {
            arrayListOf(Frame(startDir, startPath, null, null))
        } else null
    }
    val relative = cursorKey.removePrefix(startPath).trimEnd('/')
    if (relative.isEmpty()) return@withContext arrayListOf(Frame(startDir, startPath, null, null))
    val segs = relative.split('/')
    val frames = ArrayList<Frame>()
    var dir = startDir
    var path = startPath
    for (i in segs.indices) {
        val entry = lookupEntry(userId, dir, segs[i])
        if (entry == null) {
            // start-after 的名字可以不存在：用无符号最大 id 让"折叠名等于它"的条目
            // 整体被跳过，只剩 folded > bound 那一支生效。续页令牌则每段都必须在。
            if (fromToken || i < segs.lastIndex) return@withContext null
            frames += Frame(dir, path, segs[i], UUID(-1L, -1L))
            break
        }
        frames += Frame(dir, path, entry.name, entry.id)
        if (i < segs.lastIndex) {
            if (!entry.collection) return@withContext null
            dir = entry.id
            path += entry.name + "/"
        }
    }
    frames
}

/**
 * 显式栈的 DFS。内存只保留每个活跃帧的一页（≤500 条），不 materialize 整棵树。
 * 下钻前先把父帧游标推进到该目录，子树耗尽后父层从它之后继续。
 */
private class Walker(
    private val userId: UUID,
    private val stack: MutableList<Frame>,
    private val filterPath: String,
    private val namePrefix: String,
    private val delimiter: String,
    private val limit: Int,
) {
    private var emitted = 0

    suspend fun next(): Item? {
        while (stack.isNotEmpty() && emitted < limit) {
            val f = stack.last()
            if (f.index >= f.page.size) {
                val batch = withContext(Dispatchers.IO) {
                    DavListing.page(userId, f.dirId, f.afterName, f.afterId, DavListing.PAGE)
                }
                if (batch.isEmpty()) {
                    stack.removeAt(stack.lastIndex)
                    continue
                }
                f.page = batch
                f.index = 0
            }
            val e = f.page[f.index++]
            f.afterName = e.name
            f.afterId = e.id
            // 名字前缀过滤只作用于 prefix 的末段所在的那一层
            if (namePrefix.isNotEmpty() && f.path == filterPath && !e.name.startsWith(namePrefix)) continue
            if (e.collection) {
                if (delimiter == "/") {
                    emitted++
                    return Item.Prefix(f.path + e.name + "/", e.id)
                }
                stack.add(Frame(e.id, f.path + e.name + "/", null, null))
                continue
            }
            emitted++
            return Item.Obj(f.path + e.name, e.id, e)
        }
        return null
    }
}