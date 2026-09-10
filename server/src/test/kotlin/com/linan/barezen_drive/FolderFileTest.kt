package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class FolderFileTest {
    private val storageDir = java.nio.file.Files.createTempDirectory("bz-ff").toString()
    private fun cfg() = AppConfig(0, "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30)

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        val storage = LocalStorageProvider(java.nio.file.Path.of(c.storageDir))
        application { module(c, storage) }
        val reg = client.post("/api/auth/register") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        assertEquals(HttpStatusCode.Created, reg.status)
        val login = client.post("/api/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""") }
        assertEquals(HttpStatusCode.OK, login.status)
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }
    private var auth = ""

    private suspend fun ApplicationTestBuilder.mkFolder(parent: String?, name: String): String {
        val body = if (parent == null) """{"name":"$name"}""" else """{"parentId":"$parent","name":"$name"}"""
        val r = client.post("/api/folders") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Created, r.status)
        return Regex(""""id":"([^"]+)"""").find(r.bodyAsText())!!.groupValues[1]
    }

    @Test
    fun folderCrudAndConflicts() = testApplication {
        setup()
        val a = mkFolder(null, "Photos")
        val b = mkFolder(a, "2026")
        // List root: only Photos
        var list = client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(list.bodyAsText().contains("Photos")); assertTrue(!Regex(""""name":"2026"""").containsMatchIn(list.bodyAsText()))
        // Duplicate sibling name (folder AND file namespaces share one sibling namespace)
        val dup = client.post("/api/folders") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"Photos"}""") }
        assertEquals(HttpStatusCode.Conflict, dup.status); assertTrue(dup.bodyAsText().contains("NAME_CONFLICT"))
        // Rename
        val ren = client.patch("/api/folders/$b") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"2027"}""") }
        assertEquals(HttpStatusCode.OK, ren.status); assertTrue(ren.bodyAsText().contains("2027"))
        // Missing folder -> 404
        val miss = client.get("/api/folders/00000000-0000-0000-0000-000000000000/contents") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NotFound, miss.status); assertTrue(miss.bodyAsText().contains("NOT_FOUND"))
        // Recursive delete a -> b gone too
        val del = client.delete("/api/folders/$a") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NoContent, del.status)
        list = client.get("/api/folders/root/contents") { header(HttpHeaders.Authorization, auth) }
        assertFalse(list.bodyAsText().contains("Photos"))
    }

    @Test
    fun fileEndpoints404WithoutFiles() = testApplication {
        setup()
        // v0.0.1 only uploads create file records (Task 6); here we verify operations on
        // nonexistent files return 404. Positive rename/move/delete paths are covered by
        // Task 6's UploadTest with real uploaded files.
        val miss = client.patch("/api/files/00000000-0000-0000-0000-000000000000") { header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":"x"}""") }
        assertEquals(HttpStatusCode.NotFound, miss.status); assertTrue(miss.bodyAsText().contains("NOT_FOUND"))
        val delMiss = client.delete("/api/files/00000000-0000-0000-0000-000000000000") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.NotFound, delMiss.status); assertTrue(delMiss.bodyAsText().contains("NOT_FOUND"))
    }
}
