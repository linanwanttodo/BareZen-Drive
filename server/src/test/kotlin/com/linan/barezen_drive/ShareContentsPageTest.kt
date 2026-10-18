package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.FolderDto
import com.linan.barezen_drive.core.dto.ShareDto
import com.linan.barezen_drive.core.dto.SharedContentsResponse
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A shared folder's listing is a **public** endpoint, and it used to answer with
 * every folder and every file of the requested directory: read them all, sort them
 * all in Kotlin, serialise them all. One anonymous request therefore cost the server
 * an amount of work and memory that grew with whatever the owner had shared - and no
 * client could ask for less than the whole thing.
 *
 * It is a page now, ordered by the database, and the two cursors are where the next
 * page starts. Pinned here: the page never exceeds the requested size, the ordering
 * holds across pages, and a boundary neither drops nor repeats an entry.
 */
class ShareContentsPageTest {
    private val storageDir = Files.createTempDirectory("bz-sharepage").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""

    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
        registrationOpen = true,
    )

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.get("/health")
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"sharer","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"sharer","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.mkFolder(name: String, parent: String?): String {
        val parentField = parent?.let { """"parentId":"$it",""" } ?: ""
        val res = client.post("/api/folders") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{$parentField"name":"$name"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        return json.decodeFromString<FolderDto>(res.bodyAsText()).id
    }

    private suspend fun ApplicationTestBuilder.upload(folderId: String, name: String) {
        val body = name.encodeToByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"sha256":"$sha","mimeType":"text/plain","folderId":"$folderId"}""")
        }
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        client.put("/api/uploads/${ir.uploadId}/chunks/0") {
            header(HttpHeaders.Authorization, auth)
            setBody(body)
        }
        val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        json.decodeFromString<UploadCompleteResponse>(done.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.shareFolder(folderId: String): String {
        val res = client.post("/api/shares") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"folderId":"$folderId"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        // The token only ever appears in the create response; the management list masks it.
        return json.decodeFromString<ShareDto>(res.bodyAsText()).url.substringAfterLast('/')
    }

    private suspend fun ApplicationTestBuilder.contents(token: String, query: String): SharedContentsResponse {
        val res = client.get("/api/public/shares/$token/contents$query")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return json.decodeFromString<SharedContentsResponse>(res.bodyAsText())
    }

    @Test
    fun oneRequestNeverCarriesTheWholeDirectory() = testApplication {
        setup()
        val dir = mkFolder("dropbox", null)
        listOf("e.txt", "a.txt", "c.txt", "b.txt", "d.txt").forEach { upload(dir, it) }
        mkFolder("sub1", dir)
        mkFolder("sub2", dir)
        val token = shareFolder(dir)

        val first = contents(token, "?limit=2")
        assertEquals(2, first.files.size, "a page is a page, whatever the directory holds")
        assertEquals(2, first.folders.size)
        assertEquals(listOf("a.txt", "b.txt"), first.files.map { it.name }, "ordered by the name, case-folded")
        val fileCursor = assertNotNull(first.fileCursor, "more files exist, so the cursor has to say where")
        assertNull(first.folderCursor, "the two subfolders fit on this page, so there is nothing to resume")

        val second = contents(token, "?limit=2&fileCursor=$fileCursor")
        assertEquals(listOf("c.txt", "d.txt"), second.files.map { it.name })
        val third = contents(token, "?limit=2&fileCursor=${assertNotNull(second.fileCursor)}")
        assertEquals(listOf("e.txt"), third.files.map { it.name })
        assertNull(third.fileCursor, "the last page must not promise a fourth one")

        val walked = first.files + second.files + third.files
        assertEquals(5, walked.map { it.id }.distinct().size, "a boundary may not drop or repeat an entry")
    }

    @Test
    fun aGreedyLimitIsClampedInsteadOfHonoured() = testApplication {
        setup()
        val dir = mkFolder("big", null)
        (1..4).forEach { upload(dir, "f$it.txt") }
        val token = shareFolder(dir)

        val page = contents(token, "?limit=999999")
        assertTrue(page.files.size <= 4, "everything fits under the cap here: ${page.files.size}")
        val junk = contents(token, "?limit=0&fileCursor=not-a-cursor")
        assertEquals(1, junk.files.size, "limit 0 is clamped to one row, and a cursor that cannot be parsed restarts the page")
    }
}
