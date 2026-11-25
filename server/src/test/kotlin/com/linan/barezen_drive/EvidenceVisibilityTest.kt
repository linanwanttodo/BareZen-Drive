package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.AlbumPage
import com.linan.barezen_drive.core.dto.ContentsResponse
import com.linan.barezen_drive.core.dto.RecentFilesResponse
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
import kotlin.test.*

class EvidenceVisibilityTest {
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""

    private suspend fun ApplicationTestBuilder.setup() {
        val config = AppConfig(
            0,
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa",
            "",
            "test-secret-0123456789abcdef0123456789abcdef",
            Files.createTempDirectory("bz-evidence").toString(),
            1L shl 30,
        )
        application { module(config, LocalStorageProvider(java.nio.file.Path.of(config.storageDir))) }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"user1","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.folder(name: String, parentId: String? = null): String {
        val body = if (parentId == null) """{"name":"$name"}""" else """{"parentId":"$parentId","name":"$name"}"""
        val response = client.post("/api/folders") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return Regex(""""id":"([^"]+)"""").find(response.bodyAsText())!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.upload(name: String, folderId: String?, mime: String): String {
        val bytes = "$name-${UUID.randomUUID()}".encodeToByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val body = buildString {
            append("""{"name":"$name","size":${bytes.size},"mimeType":"$mime","sha256":"$sha"""")
            if (folderId != null) append(""","folderId":"$folderId"""")
            append("}")
        }
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val request = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        assertFalse(request.instantUpload)
        val put = client.put("/api/uploads/${request.uploadId}/chunks/0") {
            header(HttpHeaders.Authorization, auth)
            setBody(bytes)
        }
        assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
        val done = client.post("/api/uploads/${request.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
    }

    @Test
    fun evidenceNamespaceIsHiddenFromOrdinaryListings() = testApplication {
        setup()
        val root = folder("取证")
        val vault = folder("0123456789abcdef0123456789abcdef", root)
        val session = folder("20261125_010101_deadbeef", vault)
        upload("vault.json", vault, "application/json")
        upload("001_video.mp4.enc", session, "application/octet-stream")
        upload("private.jpg", session, "image/jpeg")
        val ordinary = folder("Photos")
        upload("visible.jpg", ordinary, "image/jpeg")
        upload("root.jpg", null, "image/jpeg")

        val rootList = json.decodeFromString<ContentsResponse>(
            client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }.bodyAsText(),
        )
        assertFalse(rootList.folders.any { it.name == "取证" })
        assertTrue(rootList.folders.any { it.name == "Photos" })

        val search = json.decodeFromString<RecentFilesResponse>(
            client.get("/api/search?q=.jpg") { header(HttpHeaders.Authorization, auth) }.bodyAsText(),
        )
        assertEquals(setOf("visible.jpg", "root.jpg"), search.files.map { it.name }.toSet())
        val recent = json.decodeFromString<RecentFilesResponse>(
            client.get("/api/files/recent?limit=50") { header(HttpHeaders.Authorization, auth) }.bodyAsText(),
        )
        assertEquals(setOf("visible.jpg", "root.jpg"), recent.files.map { it.name }.toSet())
        val album = json.decodeFromString<AlbumPage>(
            client.get("/api/album") { header(HttpHeaders.Authorization, auth) }.bodyAsText(),
        )
        assertEquals(setOf("visible.jpg", "root.jpg"), album.files.map { it.name }.toSet())

        val evidenceList = json.decodeFromString<ContentsResponse>(
            client.get("/api/folders/root/contents?includeEvidence=true") { header(HttpHeaders.Authorization, auth) }.bodyAsText(),
        )
        assertTrue(evidenceList.folders.any { it.id == root })
        val sessionList = json.decodeFromString<ContentsResponse>(
            client.get("/api/folders/$session/contents") { header(HttpHeaders.Authorization, auth) }.bodyAsText(),
        )
        assertEquals(setOf("001_video.mp4.enc", "private.jpg"), sessionList.files.map { it.name }.toSet())
    }
}
