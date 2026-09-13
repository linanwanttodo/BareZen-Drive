package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.AlbumPage
import com.linan.barezen_drive.core.dto.ContentsResponse
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.RecentFilesResponse
import com.linan.barezen_drive.core.dto.SharedContentsResponse
import com.linan.barezen_drive.core.dto.TrashResponse
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.files.FileService
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/**
 * The gallery foundations: soft delete with a trash, favorites, and archive.
 * Covers what every listing must hide, what the trash endpoints must do, and
 * the retention sweep that finally frees the bytes.
 */
class TrashFavoriteArchiveTest {
    private val storageDir = Files.createTempDirectory("bz-trash").toString()
    private fun cfg() = AppConfig(0, "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30)
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

    private suspend fun ApplicationTestBuilder.uploadImage(mark: String, name: String, folderId: String? = null): String {
        val body = mark.encodeToByteArray()
        val initBody = buildString {
            append("""{"name":"$name","size":${body.size},"mimeType":"image/jpeg","sha256":"${sha256hex(body)}"""")
            if (folderId != null) append(""","folderId":"$folderId"""")
            append("}")
        }
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody(initBody)
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        assertFalse(ir.instantUpload, "each mark must be distinct content: $mark")
        val put = client.put("/api/uploads/${ir.uploadId}/chunks/0") { header(HttpHeaders.Authorization, auth); setBody(body) }
        assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
        val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
    }

    private suspend fun ApplicationTestBuilder.mkFolder(name: String, parentId: String? = null): String {
        val body = if (parentId == null) """{"name":"$name"}""" else """{"parentId":"$parentId","name":"$name"}"""
        val res = client.post("/api/folders") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        return Regex(""""id":"([^"]+)"""").find(res.bodyAsText())!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.albumNames(vararg flags: String): List<String> {
        val res = client.get("/api/album" + if (flags.isEmpty()) "" else "?" + flags.joinToString("&")) { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        // Sorted: rows uploaded in the same millisecond have no stable ordering,
        // and these assertions are about membership, not sequence.
        return json.decodeFromString<AlbumPage>(res.bodyAsText()).files.map { it.name }.sorted()
    }

    @Test
    fun deleteMovesToTrashAndHidesEveryListing() = testApplication {
        setup()
        val folder = mkFolder("Pics")
        val id = uploadImage("keep", "pic.jpg", folder)
        assertEquals(listOf("pic.jpg"), albumNames())
        assertTrue(json.decodeFromString<ContentsResponse>(client.get("/api/folders/$folder/contents") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files.any { it.id == id })
        assertEquals(listOf("pic.jpg"), json.decodeFromString<RecentFilesResponse>(client.get("/api/files/recent") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files.map { it.name })
        assertEquals(1, json.decodeFromString<RecentFilesResponse>(client.get("/api/search?q=pic") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files.size)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/files/$id") { header(HttpHeaders.Authorization, auth) }.status)
        // Gone from every live view, present in the trash with its timestamp set.
        assertEquals(emptyList(), albumNames())
        assertTrue(json.decodeFromString<ContentsResponse>(client.get("/api/folders/$folder/contents") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files.none { it.id == id })
        assertEquals(emptyList(), json.decodeFromString<RecentFilesResponse>(client.get("/api/files/recent") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files.map { it.name })
        assertEquals(0, json.decodeFromString<RecentFilesResponse>(client.get("/api/search?q=pic") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files.size)
        val trashed = json.decodeFromString<TrashResponse>(client.get("/api/trash") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files
        assertEquals(listOf("pic.jpg"), trashed.map { it.name })
        assertNotNull(trashed.single().deletedAt, "a trash row must carry its trashed timestamp")
        assertEquals(folder, trashed.single().folderId, "the trash keeps the origin folder for the restore")

        // Restore puts it back everywhere.
        val restored = client.post("/api/trash/$id/restore") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, restored.status, restored.bodyAsText())
        assertNull(json.decodeFromString<FileDto>(restored.bodyAsText()).deletedAt)
        assertEquals(listOf("pic.jpg"), albumNames())
        assertEquals(emptyList(), json.decodeFromString<TrashResponse>(client.get("/api/trash") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files)
    }

    @Test
    fun trashedNameDoesNotBlockAReupload() = testApplication {
        setup()
        val first = uploadImage("one", "a.txt")
        // Same name, same folder, different content: only possible because the
        // trashed row leaves the live namespace.
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/files/$first") { header(HttpHeaders.Authorization, auth) }.status)
        val second = uploadImage("two", "a.txt")
        assertNotEquals(first, second)
        assertEquals(1, albumNames().size)
        assertEquals(1, json.decodeFromString<TrashResponse>(client.get("/api/trash") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files.size)
    }

    @Test
    fun restoreIntoANameClashConflicts() = testApplication {
        setup()
        val trashed = uploadImage("gone", "same.jpg")
        client.delete("/api/files/$trashed") { header(HttpHeaders.Authorization, auth) }
        uploadImage("live", "same.jpg")
        val res = client.post("/api/trash/$trashed/restore") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.Conflict, res.status, res.bodyAsText())
        assertTrue(res.bodyAsText().contains("NAME_CONFLICT"))
        // The live file is untouched and the trashed one is still recoverable.
        assertEquals(listOf("same.jpg"), albumNames())
        assertEquals(1, json.decodeFromString<TrashResponse>(client.get("/api/trash") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files.size)
    }

    @Test
    fun permanentDeleteNeedsATrashedRowAndFreesTheBlob() = testApplication {
        setup()
        val live = uploadImage("alive", "live.jpg")
        // A live row cannot be purged through the trash endpoint: that is the
        // guard that keeps a buggy client from skipping the trash.
        assertEquals(HttpStatusCode.BadRequest, client.delete("/api/trash/$live") { header(HttpHeaders.Authorization, auth) }.status)

        val doomed = uploadImage("dead", "dead.jpg")
        client.delete("/api/files/$doomed") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/trash/$doomed") { header(HttpHeaders.Authorization, auth) }.status)
        assertEquals(emptyList(), json.decodeFromString<TrashResponse>(client.get("/api/trash") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files)
        assertEquals(0L, transaction { FilesTable.selectAll().where { FilesTable.id eq UUID.fromString(doomed) }.count() })
        // The bytes went with the last reference.
        val sha = sha256hex("dead".encodeToByteArray())
        assertTrue(Files.notExists(java.nio.file.Path.of(storageDir, "blobs", sha.substring(0, 2), sha.substring(2, 4), sha)))
    }

    @Test
    fun favoriteAndArchiveFilters() = testApplication {
        setup()
        val star = uploadImage("star", "star.jpg")
        uploadImage("plain", "plain.jpg")
        val box = uploadImage("box", "box.jpg")

        val fav = client.put("/api/files/$star/favorite") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"favorite":true}""")
        }
        assertEquals(HttpStatusCode.OK, fav.status, fav.bodyAsText())
        assertTrue(json.decodeFromString<FileDto>(fav.bodyAsText()).isFavorite)
        assertEquals(listOf("star.jpg"), albumNames("favorite=true"))

        val arch = client.put("/api/files/$box/archive") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"archived":true}""")
        }
        assertEquals(HttpStatusCode.OK, arch.status, arch.bodyAsText())
        assertNotNull(json.decodeFromString<FileDto>(arch.bodyAsText()).archivedAt)
        // The default timeline drops the archived one, the archived view shows only it.
        assertEquals(listOf("plain.jpg", "star.jpg"), albumNames())
        assertEquals(listOf("box.jpg"), albumNames("archived=true"))
        // Archived rows stay out of search and the recent strip as well.
        assertEquals(0, json.decodeFromString<RecentFilesResponse>(client.get("/api/search?q=box") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files.size)
        assertFalse(json.decodeFromString<RecentFilesResponse>(client.get("/api/files/recent") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files.any { it.name == "box.jpg" })

        // Unarchive brings it back; unfavorite empties the favorite view.
        client.put("/api/files/$box/archive") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"archived":false}""") }
        assertEquals(listOf("box.jpg", "plain.jpg", "star.jpg"), albumNames())
        client.put("/api/files/$star/favorite") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"favorite":false}""") }
        assertEquals(emptyList(), albumNames("favorite=true"))
    }

    @Test
    fun retentionSweepPurgesExpiredTrash() = testApplication {
        setup()
        val old = uploadImage("old", "old.jpg")
        val fresh = uploadImage("fresh", "fresh.jpg")
        client.delete("/api/files/$old") { header(HttpHeaders.Authorization, auth) }
        client.delete("/api/files/$fresh") { header(HttpHeaders.Authorization, auth) }
        val thirtyOneDays = 31L * 24 * 3600 * 1000
        transaction {
            FilesTable.update({ FilesTable.id eq UUID.fromString(old) }) { it[deletedAt] = System.currentTimeMillis() - thirtyOneDays }
        }
        val freed = FileService.purgeExpiredTrash()
        assertEquals(1, freed.size, "only the expired row releases its blob")
        assertEquals(1, json.decodeFromString<TrashResponse>(client.get("/api/trash") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files.size)
        assertEquals("fresh.jpg", json.decodeFromString<TrashResponse>(client.get("/api/trash") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files.single().name)
        // A full purge empties what is left.
        assertEquals(1, FileService.purgeExpiredTrash(retentionDays = 0).size)
        assertEquals(emptyList(), json.decodeFromString<TrashResponse>(client.get("/api/trash") { header(HttpHeaders.Authorization, auth) }.bodyAsText()).files)
    }

    @Test
    fun trashEndpointsRequireAuth() = testApplication {
        setup()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/trash").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/trash").status)
    }

    /**
     * Trashing revokes the shares that point at the file itself, but a folder
     * link keeps existing, so the public read paths have to filter the soft
     * deleted rows: a photo in the trash must neither be listed nor served
     * through the folder it was shared in.
     */
    @Test
    fun trashedFileDisappearsFromFolderShare() = testApplication {
        setup()
        val folder = mkFolder("Album")
        val id = uploadImage("shared", "pic.jpg", folder)
        val create = client.post("/api/shares") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json)
            setBody("""{"folderId":"$folder"}""")
        }
        assertEquals(HttpStatusCode.Created, create.status, create.bodyAsText())
        val token = Regex(""""url":"/s/([^"]+)"""").find(create.bodyAsText())!!.groupValues[1]

        // On the shelf: listed and downloadable through the link.
        val before = client.get("/api/public/shares/$token/contents?folder=$folder")
        assertEquals(HttpStatusCode.OK, before.status, before.bodyAsText())
        assertTrue(before.bodyAsText().contains("pic.jpg"), before.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.get("/api/public/shares/$token/files/$id/content").status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/files/$id") { header(HttpHeaders.Authorization, auth) }.status)
        val after = json.decodeFromString<SharedContentsResponse>(
            client.get("/api/public/shares/$token/contents?folder=$folder").bodyAsText(),
        )
        assertTrue(after.files.none { it.id == id }, "a trashed row must leave the share listing")
        assertEquals(HttpStatusCode.NotFound, client.get("/api/public/shares/$token/files/$id/content").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/public/shares/$token/files/$id/thumbnail").status)

        // Restoring re-publishes it on the very same link.
        assertEquals(HttpStatusCode.OK, client.post("/api/trash/$id/restore") { header(HttpHeaders.Authorization, auth) }.status)
        val back = json.decodeFromString<SharedContentsResponse>(
            client.get("/api/public/shares/$token/contents?folder=$folder").bodyAsText(),
        )
        assertEquals(listOf(id), back.files.map { it.id })
    }
}
