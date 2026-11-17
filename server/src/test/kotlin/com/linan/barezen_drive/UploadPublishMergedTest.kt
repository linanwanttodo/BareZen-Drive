package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.UploadInitRequest
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FileVersionsTable
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.files.FileService
import com.linan.barezen_drive.files.UploadDigests
import com.linan.barezen_drive.files.UploadService
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * publishMerged 抽取的硬证据：合并后的字节必须与输入逐字节一致，任何一片的
 * 哈希/长度不符都必须在落行之前失败。现有 UploadDedupSizeTest / DavPutTest
 * 覆盖的场景重构后必须原样全绿——它们才是"行为保持"的判据。
 */
class UploadPublishMergedTest {
    private val storageDir = Files.createTempDirectory("bz-merge").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""
    private var uid = UUID.randomUUID()

    private suspend fun ApplicationTestBuilder.setup() {
        val cfg = AppConfig(
            0, "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30, registrationOpen = true,
        )
        application { module(cfg, LocalStorageProvider(Path.of(cfg.storageDir))) }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"user1","password":"password123"}""")
            }.bodyAsText(),
        )!!.groupValues[1]
        uid = transaction(DatabaseFactory.db) {
            UsersTable.selectAll().where { UsersTable.username eq "user1" }.single()[UsersTable.id]
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text) as JsonObject

    private fun str(o: JsonObject, k: String): String = o[k]!!.jsonPrimitive.content

    /** 走真实 API 上传 [chunks]，返回 file id；chunkSize 用最小值以便多片。 */
    private suspend fun ApplicationTestBuilder.upload(
        name: String,
        chunks: List<ByteArray>,
        sha: String? = null,
        userMeta: String? = null,
        overwrite: Boolean = false,
        folderId: UUID? = null,
    ): String {
        val sid = initSession(name, chunks.sumOf { it.size.toLong() }, sha, userMeta, overwrite, folderId)
        chunks.forEachIndexed { i, bytes ->
            val r = client.put("/api/uploads/$sid/chunks/$i") { header(HttpHeaders.Authorization, auth); setBody(bytes) }
            assertEquals(HttpStatusCode.NoContent, r.status, r.bodyAsText())
        }
        val done = client.post("/api/uploads/$sid/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return str(obj(done.bodyAsText())["file"] as JsonObject, "id")
    }

    private suspend fun ApplicationTestBuilder.initSession(
        name: String, total: Long, sha: String? = null, userMeta: String? = null,
        overwrite: Boolean = false, folderId: UUID? = null,
    ): String {
        val body = buildString {
            append("{")
            if (folderId != null) append(""""folderId":"$folderId",""")
            append(""""name":${json.encodeToString(name)},"size":$total,"chunkSize":${UploadService.MIN_CHUNK}""")
            if (sha != null) append(""","sha256":"$sha"""")
            if (userMeta != null) append(""","userMeta":${json.encodeToString(userMeta)}""")
            if (overwrite) append(""","overwrite":true""")
            append("}")
        }
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody(body)
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        return str(obj(init.bodyAsText()), "uploadId")
    }

    private fun contentOf(id: String): ByteArray {
        val key = transaction(DatabaseFactory.db) {
            FilesTable.selectAll().where { FilesTable.id eq UUID.fromString(id) }.single()[FilesTable.storageKey]
        }
        return Path.of(storageDir).resolve(key).toFile().readBytes()
    }

    private fun metaOf(id: String): String? = transaction(DatabaseFactory.db) {
        FilesTable.selectAll().where { FilesTable.id eq UUID.fromString(id) }.single()[FilesTable.userMeta]
    }

    private fun liveId(name: String): UUID = transaction(DatabaseFactory.db) {
        FilesTable.selectAll().where { (FilesTable.user eq uid) and (FilesTable.name eq name) and (FilesTable.deletedAt eq 0L) }
            .single()[FilesTable.id]
    }

    private fun versionsOf(fileId: UUID): Long = transaction(DatabaseFactory.db) {
        FileVersionsTable.selectAll().where { FileVersionsTable.file eq fileId }.count()
    }

    private fun fileCount(): Long = transaction(DatabaseFactory.db) { FilesTable.selectAll().count() }

    @Test
    fun mergedBytesAreExactlyTheConcatenation() = testApplication {
        setup()
        val a = ByteArray(UploadService.MIN_CHUNK.toInt()) { (it % 251).toByte() }
        val b = "tail".toByteArray()
        val id = upload("multi.bin", listOf(a, b))
        assertTrue((a + b).contentEquals(contentOf(id)), "合并后的字节与输入不一致")
    }

    @Test
    fun aWrongWholeFileHashPublishesNothing() = testApplication {
        setup()
        val before = fileCount()
        val sid = initSession("bad.bin", 5L, sha = "0".repeat(64))
        client.put("/api/uploads/$sid/chunks/0") { header(HttpHeaders.Authorization, auth); setBody("hello".toByteArray()) }
        val done = client.post("/api/uploads/$sid/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.BadRequest, done.status)
        assertEquals(before, fileCount())
    }

    @Test
    fun aTruncatedStagedPartPublishesNothing() = testApplication {
        setup()
        val before = fileCount()
        val sid = initSession("short.bin", 5L)
        client.put("/api/uploads/$sid/chunks/0") { header(HttpHeaders.Authorization, auth); setBody("hello".toByteArray()) }
        // 暂存片被截断（掉电、磁盘满、被别的进程改过）。旧代码信任 putChunk 的校验，
        // 会合并出 2 字节却以 size=5 落库；新检查必须在落行之前拦住。
        Path.of(storageDir, "tmp", sid, "0.part").toFile().writeText("he")
        val done = client.post("/api/uploads/$sid/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.BadRequest, done.status, done.bodyAsText())
        assertEquals(before, fileCount())
    }

    @Test
    fun aStagedPartThatGrewPublishesNothing() = testApplication {
        setup()
        val before = fileCount()
        val sid = initSession("long.bin", 5L)
        client.put("/api/uploads/$sid/chunks/0") { header(HttpHeaders.Authorization, auth); setBody("hello".toByteArray()) }
        Path.of(storageDir, "tmp", sid, "0.part").toFile().writeText("hello world")
        val done = client.post("/api/uploads/$sid/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.BadRequest, done.status, done.bodyAsText())
        assertEquals(before, fileCount())
    }

    @Test
    fun verifyRunsBeforeTheBlobIsPromoted() = testApplication {
        setup()
        val storage = LocalStorageProvider(Path.of(storageDir))
        val bytes = "hello".toByteArray()
        val sid = initSession("v.txt", bytes.size.toLong())
        client.put("/api/uploads/$sid/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(bytes) }
        val before = fileCount()
        val err = assertFailsWith<IllegalStateException> {
            UploadService.completeWithDigests(uid, UUID.fromString(sid), storage, true, UploadDigests()) {
                throw IllegalStateException("checksum mismatch")
            }
        }
        assertEquals("checksum mismatch", err.message)
        assertEquals(before, fileCount())
        // 校验和核对绝不能先落一个半截 blob：verify 必须在提升之前。
        assertFalse(storage.exists(storage.blobKey(sha256Hex(bytes))), "verify 失败却已经提升了 blob")
    }

    @Test
    fun instantOverwriteCanSkipTheVersionSnapshot() = testApplication {
        setup()
        val storage = LocalStorageProvider(Path.of(storageDir))
        upload("over.txt", listOf("one".toByteArray()))
        upload("donor.txt", listOf("two".toByteArray()))
        val fileId = liveId("over.txt")
        val req = { sha: String -> UploadInitRequest(name = "over.txt", size = 3, sha256 = sha, overwrite = true) }

        val noSnapshot = UploadService.initUpload(uid, req(sha256Hex("two".toByteArray())), storage, snapshotOnOverwrite = false)
        assertTrue(noSnapshot.instantUpload)
        assertEquals(0L, versionsOf(fileId), "snapshotOnOverwrite=false 不该造版本")

        val withSnapshot = UploadService.initUpload(uid, req(sha256Hex("one".toByteArray())), storage, snapshotOnOverwrite = true)
        assertTrue(withSnapshot.instantUpload)
        assertEquals(1L, versionsOf(fileId), "默认行为必须仍然是留一条版本")
        assertEquals(fileId, liveId("over.txt"), "覆盖必须保住行 id")
    }

    @Test
    fun userMetaLandsOnTheFileRow() = testApplication {
        setup()
        val id = upload("meta.txt", listOf("ok".toByteArray()), userMeta = """{"mtime":"1.0"}""")
        assertEquals("""{"mtime":"1.0"}""", metaOf(id))
    }

    @Test
    fun userMetaLandsOnInstantUpload() = testApplication {
        setup()
        val storage = LocalStorageProvider(Path.of(storageDir))
        upload("donor.txt", listOf("hi".toByteArray()))
        val r = UploadService.initUpload(
            uid,
            UploadInitRequest(name = "instant.txt", size = 2, sha256 = sha256Hex("hi".toByteArray()), userMeta = """{"a":"1"}"""),
            storage,
        )
        assertTrue(r.instantUpload)
        assertEquals("""{"a":"1"}""", metaOf(r.file!!.id))
        // 相同字节的秒传覆盖是 no-op 分支，元数据同样要替换而不是留着旧值
        val again = UploadService.initUpload(
            uid,
            UploadInitRequest(name = "instant.txt", size = 2, sha256 = sha256Hex("hi".toByteArray()), overwrite = true, userMeta = """{"a":"2"}"""),
            storage,
        )
        assertTrue(again.instantUpload)
        assertEquals("""{"a":"2"}""", metaOf(again.file!!.id))
    }

    @Test
    fun overwriteReplacesUserMetaRatherThanMerging() = testApplication {
        setup()
        val first = upload("m.txt", listOf("one".toByteArray()), userMeta = """{"a":"1","b":"2"}""")
        val second = upload("m.txt", listOf("two".toByteArray()), userMeta = """{"c":"3"}""", overwrite = true)
        assertEquals(first, second)
        assertEquals("""{"c":"3"}""", metaOf(second), "S3 语义是替换不是合并")
        upload("m.txt", listOf("three".toByteArray()), overwrite = true)
        assertNull(metaOf(second), "新请求没带元数据就清空")
        // 相同字节的覆盖走另一支（只 bump updatedAt），元数据也不能漏
        upload("m.txt", listOf("three".toByteArray()), userMeta = """{"d":"4"}""", overwrite = true)
        assertEquals("""{"d":"4"}""", metaOf(second))
    }

    @Test
    fun copyCarriesUserMeta() = testApplication {
        setup()
        val id = upload("src.txt", listOf("x".toByteArray()), userMeta = """{"k":"v"}""")
        val copied = FileService.copyFile(uid, UUID.fromString(id), null, "copy.txt")
        assertEquals("""{"k":"v"}""", metaOf(copied.toString()))

        val folder = UUID.randomUUID()
        transaction(DatabaseFactory.db) {
            FoldersTable.insert {
                it[FoldersTable.id] = folder; it[FoldersTable.user] = uid
                it[FoldersTable.parent] = null; it[FoldersTable.name] = "tree"
            }
        }
        upload("leaf.txt", listOf("y".toByteArray()), userMeta = """{"k":"v"}""", folderId = folder)
        val newRoot = FileService.copySubtree(uid, folder, null, "tree2")
        assertEquals("""{"k":"v"}""", transaction(DatabaseFactory.db) {
            FilesTable.selectAll().where { (FilesTable.folder eq newRoot) and (FilesTable.name eq "leaf.txt") }
                .single()[FilesTable.userMeta]
        })
    }
}