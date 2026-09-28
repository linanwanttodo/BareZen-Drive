package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.ContentsResponse
import com.linan.barezen_drive.core.dto.CreateFolderRequest
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

/**
 * `contents` used to answer with the whole folder: a directory of ten thousand
 * files was one response, one deserialization and one full list replacement on
 * the client, and every list scroll rebound every item. Paging is opt-in by
 * asking for a `limit`, which keeps the many `contents()` callers that only
 * read `folders` (album categories, platform lookup) byte-identical to before.
 *
 * The cursor is the same shape the album endpoint uses - "<sortKey>:<id>" - so
 * paging is stable when a file is renamed or added mid-scroll: a row can be
 * skipped or repeated by at most one, never silently lost.
 */
class ContentsPagingTest {
    private val storageDir = Files.createTempDirectory("bz-page").toString()

    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
    )
    private var auth = ""
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.get("/health")
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"pager","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"pager","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    private fun sha256hex(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private suspend fun ApplicationTestBuilder.upload(name: String): String {
        val body = name.encodeToByteArray()
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"sha256":"${sha256hex(body)}","mimeType":"text/plain"}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        if (!ir.instantUpload) {
            val put = client.put("/api/uploads/${ir.uploadId}/chunks/0") {
                header(HttpHeaders.Authorization, auth)
                setBody(body)
            }
            assertEquals(HttpStatusCode.NoContent, put.status, put.bodyAsText())
            val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
            return json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
        }
        return json.decodeFromString<UploadCompleteResponse>(init.bodyAsText()).file.id
    }

    private suspend fun ApplicationTestBuilder.contents(
        id: String = "root",
        limit: Int? = null,
        cursor: String? = null,
    ): ContentsResponse {
        val query = buildString {
            if (limit != null) append("?limit=$limit")
            if (cursor != null) append(if (isEmpty()) "?" else "&").append("cursor=$cursor")
        }
        val res = client.get("/api/folders/$id/contents$query") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return json.decodeFromString(res.bodyAsText())
    }

    @Test
    fun withoutALimitTheWholeFolderComesBack() = testApplication {
        setup()
        repeat(5) { upload("bulk-$it.txt") }
        val all = contents()
        assertEquals(5, all.files.size)
        assertNull(all.nextCursor, "an unpaged request must not advertise a cursor")
    }

    @Test
    fun aLimitPagesTheFilesAndTheCursorWalksThemAll() = testApplication {
        setup()
        // 250 rows with names that sort differently from the upload order.
        val names = (1..250).map { "f-${(it * 7919) % 250}.txt" }.distinct().let {
            if (it.size == 250) it else (it + (1..250).map { "x-$it.txt" }).distinct().take(250)
        }
        names.forEach { upload(it) }

        val expected = contents().files.map { it.id }.toSet()
        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = contents(limit = 100, cursor = cursor)
            assertTrue(page.files.size <= 100, "a page may not exceed the limit")
            seen += page.files.map { it.id }
            cursor = page.nextCursor
            pages++
            assertTrue(pages < 10, "the cursor must terminate")
        } while (cursor != null)

        assertEquals(3, pages, "250 rows at 100 per page is three requests")
        assertEquals(250, seen.size)
        assertEquals(250, seen.distinct().size, "a page boundary must not repeat a file")
        assertEquals(expected, seen.toSet(), "paging must cover exactly the same set")
    }

    @Test
    fun foldersAlwaysComeBackWhole() = testApplication {
        setup()
        repeat(4) {
            client.post("/api/folders") {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"dir-$it"}""")
            }
        }
        repeat(5) { upload("p-$it.txt") }
        val page = contents(limit = 2)
        assertEquals(2, page.files.size, "the file page honours the limit")
        assertEquals(4, page.folders.size, "folders are not paged: a client must still see every folder")
    }

    @Test
    fun pageOrderMatchesTheUnpagedOrder() = testApplication {
        setup()
        // 30 distinct names, deliberately not in sorted order.
        (1..30).forEach { upload("row-${(it * 7) % 31}-pad-$it.txt") }
        val all = contents().files.map { it.id }
        val first = contents(limit = 10)
        val second = contents(limit = 10, cursor = first.nextCursor)
        assertEquals(all.take(10), first.files.map { it.id }, "the first page is the head of the list")
        assertEquals(all.drop(10).take(10), second.files.map { it.id }, "the cursor continues where page 1 stopped")
    }

    @Test
    fun aMalformedCursorRestartsFromTheTop() = testApplication {
        setup()
        repeat(5) { upload("m-$it.txt") }
        val page = contents(limit = 2, cursor = "not-a-cursor")
        assertEquals(2, page.files.size)
        // Same contract as the album endpoint: garbage in, full list from the top.
        assertEquals(contents(limit = 2).files.map { it.id }, page.files.map { it.id })
    }

    @Test
    fun pagingWorksInsideAFolderToo() = testApplication {
        setup()
        val folder = json.decodeFromString<com.linan.barezen_drive.core.dto.FolderDto>(
            client.post("/api/folders") {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody("""{"name":"many"}""")
            }.bodyAsText(),
        ).id
        repeat(12) { i ->
            val body = "inner-$i".encodeToByteArray()
            val init = client.post("/api/uploads/init") {
                header(HttpHeaders.Authorization, auth)
                contentType(ContentType.Application.Json)
                setBody("""{"folderId":"$folder","name":"i-$i.txt","size":${body.size},"sha256":"${sha256hex(body)}","mimeType":"text/plain"}""")
            }
            val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
            if (!ir.instantUpload) {
                client.put("/api/uploads/${ir.uploadId}/chunks/0") {
                    header(HttpHeaders.Authorization, auth)
                    setBody(body)
                }
                client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
            }
        }
        assertEquals(12, contents(folder).files.size)
        val p1 = contents(folder, limit = 5)
        assertEquals(5, p1.files.size)
        assertNotNull(p1.nextCursor)
        val p2 = contents(folder, limit = 5, cursor = p1.nextCursor)
        assertEquals(5, p2.files.size)
        assertEquals(12, (p1.files + p2.files + contents(folder, limit = 5, cursor = p2.nextCursor).files).distinct().size)
    }
}
