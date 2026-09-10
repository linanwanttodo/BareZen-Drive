package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.AlbumPage
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import com.linan.barezen_drive.db.FilesTable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.*

class AlbumTest {
    private val storageDir = Files.createTempDirectory("bz-album").toString()
    private fun cfg() = AppConfig(0, "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30)
    private var auth = ""
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        val storage = LocalStorageProvider(java.nio.file.Path.of(c.storageDir))
        application { module(c, storage) }
        client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        val login = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    private fun sha256hex(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private suspend fun ApplicationTestBuilder.upload(body: ByteArray, name: String, mime: String): String {
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"mimeType":"$mime","sha256":"${sha256hex(body)}"}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        assertFalse(ir.instantUpload, "content must be unique per call: $name")
        val put = client.put("/api/uploads/${ir.uploadId}/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(body) }
        assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
        val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
    }

    @Test
    fun imagesOnlySortedDesc() = testApplication {
        setup()
        upload("a".encodeToByteArray(), "newest.jpg", "image/jpeg")
        upload("b".encodeToByteArray(), "oldest.jpg", "image/jpeg")
        upload("c".encodeToByteArray(), "note.txt", "text/plain")
        upload("d".encodeToByteArray(), "clip.mp4", "video/mp4")
        // Make the ordering deterministic even when uploads land in the same millisecond.
        transaction {
            for (name in listOf("newest.jpg", "oldest.jpg")) {
                val id = FilesTable.selectAll().where { FilesTable.name eq name }.single()[FilesTable.id]
                val bump = if (name == "newest.jpg") 20_000L else 10_000L
                FilesTable.update({ FilesTable.id eq id }) { it[updatedAt] = System.currentTimeMillis() + bump }
            }
        }
        val res = client.get("/api/album") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val page = json.decodeFromString<AlbumPage>(res.bodyAsText())
        assertEquals(listOf("newest.jpg", "oldest.jpg"), page.files.map { it.name })
    }

    @Test
    fun keysetPaginationWalksForward() = testApplication {
        setup()
        // Five images, artificially aged apart so updatedAt order is deterministic.
        val names = listOf("i1.jpg", "i2.jpg", "i3.jpg", "i4.jpg", "i5.jpg")
        val ids = names.map { upload(it.encodeToByteArray(), it, "image/jpeg") }
        transaction {
            names.forEachIndexed { i, name ->
                val id = FilesTable.selectAll().where { FilesTable.name eq name }.single()[FilesTable.id]
                FilesTable.update({ FilesTable.id eq id }) {
                    it[updatedAt] = System.currentTimeMillis() + i * 10_000L
                }
            }
        }
        val p1 = client.get("/api/album?limit=2") { header(HttpHeaders.Authorization, auth) }
        val page1 = json.decodeFromString<AlbumPage>(p1.bodyAsText())
        assertEquals(listOf("i5.jpg", "i4.jpg"), page1.files.map { it.name })
        assertNotNull(page1.nextCursor)

        val p2 = client.get("/api/album?limit=2&before=${page1.nextCursor}") { header(HttpHeaders.Authorization, auth) }
        val page2 = json.decodeFromString<AlbumPage>(p2.bodyAsText())
        assertEquals(listOf("i3.jpg", "i2.jpg"), page2.files.map { it.name })

        val p3 = client.get("/api/album?limit=2&before=${page2.nextCursor}") { header(HttpHeaders.Authorization, auth) }
        val page3 = json.decodeFromString<AlbumPage>(p3.bodyAsText())
        assertEquals(listOf("i1.jpg"), page3.files.map { it.name })
        assertNull(page3.nextCursor)
        assertTrue(ids.isNotEmpty())
    }

    @Test
    fun limitClampedAndAuthRequired() = testApplication {
        setup()
        upload("x".encodeToByteArray(), "x.jpg", "image/jpeg")
        val res = client.get("/api/album?limit=9999") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals(1, json.decodeFromString<AlbumPage>(res.bodyAsText()).files.size)
        val anon = client.get("/api/album")
        assertEquals(HttpStatusCode.Unauthorized, anon.status)
    }

    @Test
    fun albumScopedToRootSubtree() = testApplication {
        setup()
        // Two folder subtrees, one image each, plus a root-layer image.
        suspend fun mkFolder(parent: String?, name: String): String {
            val parentField = parent?.let { """"parentId":"$it",""" } ?: ""
            val r = client.post("/api/folders") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{$parentField"name":"$name"}""") }
            assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
            return Regex(""""id":"([^"]+)"""").find(r.bodyAsText())!!.groupValues[1]
        }
        val albumA = mkFolder(null, "相册")
        val web = mkFolder(albumA, "Web")
        val other = mkFolder(null, "其他")
        upload("w".encodeToByteArray(), "web.jpg", "image/jpeg")
        // Move web.jpg into the album root/Web folder via the update endpoint.
        val webFileId = json.decodeFromString<AlbumPage>(client.get("/api/album") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files.first().id
        val move = client.patch("/api/files/$webFileId") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"folderId":"$web"}""") }
        assertEquals(HttpStatusCode.OK, move.status, move.bodyAsText())
        upload("o".encodeToByteArray(), "other.jpg", "image/jpeg")
        val otherFileId = json.decodeFromString<AlbumPage>(client.get("/api/album") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files.first { it.name == "other.jpg" }.id
        client.patch("/api/files/$otherFileId") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"folderId":"$other"}""") }
        // A root-layer image stays outside any folder subtree.
        upload("r".encodeToByteArray(), "root.jpg", "image/jpeg")

        // Scoped to the album root: only web.jpg (the subtree includes the Web device folder).
        val scoped = client.get("/api/album?root=$albumA") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, scoped.status, scoped.bodyAsText())
        assertEquals(listOf("web.jpg"), json.decodeFromString<AlbumPage>(scoped.bodyAsText()).files.map { it.name })

        // Scoped to the other folder: only other.jpg.
        val scopedOther = client.get("/api/album?root=$other") { header(HttpHeaders.Authorization, auth) }
        assertEquals(listOf("other.jpg"), json.decodeFromString<AlbumPage>(scopedOther.bodyAsText()).files.map { it.name })

        // Unscoped: everything (root-layer image included).
        val all = json.decodeFromString<AlbumPage>(client.get("/api/album") { header(HttpHeaders.Authorization, auth) }.bodyAsText())
        assertEquals(3, all.files.size)
    }
}
