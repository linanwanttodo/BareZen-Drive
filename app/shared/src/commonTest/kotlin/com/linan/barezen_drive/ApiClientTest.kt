package com.linan.barezen_drive

import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.UploadInitRequest
import com.linan.barezen_drive.data.api.ApiClient
import com.linan.barezen_drive.data.api.ApiFailure
import com.linan.barezen_drive.data.local.TokenStore
import com.linan.barezen_drive.data.repo.AuthRepository
import com.linan.barezen_drive.data.repo.FilesRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeTokenStore : TokenStore {
    override var baseUrl: String = "http://test"
    override var accessToken: String? = null
    override var refreshToken: String? = null
    override fun clear() {
        accessToken = null
        refreshToken = null
    }
}

private fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(body, status, headersOf("Content-Type", "application/json"))

class ApiClientTest {

    @Test
    fun loginSuccessStoresTokens() = runTest {
        val store = FakeTokenStore()
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Post && req.url.encodedPath == "/api/auth/login") {
                json("""{"accessToken":"a1","refreshToken":"r1","user":{"id":"u1","username":"n1","createdAt":"t"}}""")
            } else {
                json("""{"error":{"code":"UNEXPECTED","message":"x"}}""", HttpStatusCode.InternalServerError)
            }
        }
        val repo = AuthRepository(ApiClient(store, engine), store)
        val res = repo.login("http://test:8080", "n1", "pw")
        assertTrue(res.isSuccess)
        assertEquals("n1", res.getOrNull()!!.username)
        assertEquals("a1", store.accessToken)
        assertEquals("r1", store.refreshToken)
        assertEquals("http://test:8080", store.baseUrl)
    }

    @Test
    fun loginFailureSurfacesServerErrorCode() = runTest {
        val store = FakeTokenStore()
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Post && req.url.encodedPath == "/api/auth/login") {
                json("""{"error":{"code":"INVALID_CREDENTIALS","message":"bad"}}""", HttpStatusCode.Unauthorized)
            } else {
                json("""{"error":{"code":"UNEXPECTED","message":"x"}}""", HttpStatusCode.InternalServerError)
            }
        }
        val repo = AuthRepository(ApiClient(store, engine), store)
        val res = repo.login("http://test:8080", "n1", "pw")
        assertTrue(res.isFailure)
        val f = res.exceptionOrNull() as ApiFailure
        assertEquals("INVALID_CREDENTIALS", f.code)
        assertEquals(401, f.httpStatus)
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
    }

    @Test
    fun registerSetsBaseUrlAndSucceeds() = runTest {
        val store = FakeTokenStore()
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Post && req.url.encodedPath == "/api/auth/register") {
                json("")
            } else {
                json("""{"error":{"code":"UNEXPECTED","message":"x"}}""", HttpStatusCode.InternalServerError)
            }
        }
        val repo = AuthRepository(ApiClient(store, engine), store)
        val res = repo.register("http://test:8080", "n1", "pw")
        assertTrue(res.isSuccess)
        assertEquals("http://test:8080", store.baseUrl)
    }

    @Test
    fun refreshOn401ThenReplay() = runTest {
        val store = FakeTokenStore().apply {
            accessToken = "old"
            refreshToken = "r-old"
        }
        var refreshCalls = 0
        var sawNewBearer = false
        val engine = MockEngine { req ->
            val auth = req.headers["Authorization"]
            when {
                req.method == HttpMethod.Post && req.url.encodedPath == "/api/auth/refresh" -> {
                    refreshCalls++
                    json("""{"accessToken":"new","refreshToken":"r-new"}""")
                }
                auth == "Bearer new" -> {
                    sawNewBearer = req.method == HttpMethod.Get && req.url.encodedPath == "/api/me"
                    json("""{"id":"u1","username":"n1","createdAt":"t"}""")
                }
                else -> json("""{"error":{"code":"TOKEN_INVALID","message":"expired"}}""", HttpStatusCode.Unauthorized)
            }
        }
        val api = ApiClient(store, engine)
        val me = api.me()
        assertTrue(me.isSuccess, "me should succeed after refresh, got $me")
        assertEquals("n1", me.getOrNull()!!.username)
        assertEquals("new", store.accessToken)
        assertEquals("r-new", store.refreshToken)
        assertEquals(1, refreshCalls)
        assertTrue(sawNewBearer)
    }

    @Test
    fun refreshFailureClearsTokensAndFailsUnauthorized() = runTest {
        val store = FakeTokenStore().apply {
            accessToken = "old"
            refreshToken = "r-old"
        }
        val engine = MockEngine { req ->
            when {
                req.method == HttpMethod.Post && req.url.encodedPath == "/api/auth/refresh" ->
                    json("""{"error":{"code":"TOKEN_INVALID","message":"expired"}}""", HttpStatusCode.Unauthorized)
                else -> respond("", HttpStatusCode.Unauthorized)
            }
        }
        val api = ApiClient(store, engine)
        val res = api.me()
        assertTrue(res.isFailure)
        val f = res.exceptionOrNull() as ApiFailure
        assertTrue(f is ApiFailure.Unauthorized, "expected Unauthorized, got $f")
        assertEquals(401, f.httpStatus)
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
    }

    @Test
    fun createFolderConflictMapsErrorCode() = runTest {
        val store = FakeTokenStore()
        val engine = MockEngine { _ ->
            json("""{"error":{"code":"NAME_CONFLICT","message":"x"}}""", HttpStatusCode.Conflict)
        }
        val api = ApiClient(store, engine)
        val res = api.createFolder(null, "dup")
        assertTrue(res.isFailure)
        val f = res.exceptionOrNull() as ApiFailure
        assertEquals("NAME_CONFLICT", f.code)
        assertEquals(409, f.httpStatus)
    }

    @Test
    fun contentsParsesResponse() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Get && req.url.encodedPath == "/api/folders/f1/contents") {
                json(
                    """{"folder":{"id":"f1","name":"root","parentId":null,"createdAt":"c","updatedAt":"u"},""" +
                        """"folders":[{"id":"f2","name":"sub","parentId":"f1","createdAt":"c","updatedAt":"u"}],""" +
                        """"files":[{"id":"p1","name":"a.txt","folderId":"f1","size":3,"mimeType":"text/plain",""" +
                        """"sha256":"aa","createdAt":"c","updatedAt":"u"}]}"""
                )
            } else {
                json("""{"error":{"code":"UNEXPECTED","message":"x"}}""", HttpStatusCode.InternalServerError)
            }
        }
        val repo = FilesRepository(ApiClient(store, engine))
        val res = repo.contents("f1")
        assertTrue(res.isSuccess)
        val body = res.getOrNull()!!
        assertEquals("f1", body.folder!!.id)
        assertEquals(1, body.folders.size)
        assertEquals("sub", body.folders[0].name)
        assertEquals(1, body.files.size)
        assertEquals(3L, body.files[0].size)
    }

    @Test
    fun contentsNotFoundMapsToApiFailure() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        val engine = MockEngine { req ->
            if (req.method == HttpMethod.Get && req.url.encodedPath == "/api/folders/f404/contents") {
                json("""{"error":{"code":"NOT_FOUND","message":"missing"}}""", HttpStatusCode.NotFound)
            } else {
                json("""{"error":{"code":"UNEXPECTED","message":"x"}}""", HttpStatusCode.InternalServerError)
            }
        }
        val repo = FilesRepository(ApiClient(store, engine))
        val res = repo.contents("f404")
        assertTrue(res.isFailure)
        val f = res.exceptionOrNull() as ApiFailure
        assertEquals("NOT_FOUND", f.code)
        assertEquals(404, f.httpStatus)
    }

    @Test
    fun uploadFlowHappyPath() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        var sawChunkHeader: String? = null
        var sawChunkPath = false
        val fileJson = """{"id":"p1","name":"a.bin","folderId":null,"size":4,"mimeType":null,"sha256":"aa","createdAt":"c","updatedAt":"u"}"""
        val engine = MockEngine { req ->
            when {
                req.method == HttpMethod.Post && req.url.encodedPath == "/api/uploads/init" ->
                    json("""{"uploadId":"up1","chunkSize":10,"receivedChunks":[],"instantUpload":false,"file":null}""")
                req.method == HttpMethod.Put && req.url.encodedPath == "/api/uploads/up1/chunks/0" -> {
                    sawChunkPath = true
                    sawChunkHeader = req.headers["X-Chunk-Sha256"]
                    json("")
                }
                req.method == HttpMethod.Post && req.url.encodedPath == "/api/uploads/up1/complete" ->
                    json("""{"file":$fileJson}""")
                else -> json("""{"error":{"code":"UNEXPECTED","message":"x"}}""", HttpStatusCode.InternalServerError)
            }
        }
        val repo = FilesRepository(ApiClient(store, engine))
        val initRes = repo.init(UploadInitRequest(folderId = null, name = "a.bin", size = 4, sha256 = "aa"))
        assertTrue(initRes.isSuccess)
        assertEquals("up1", initRes.getOrNull()!!.uploadId)
        val putRes = repo.putChunk("up1", 0, byteArrayOf(1, 2, 3, 4), "aa")
        assertTrue(putRes.isSuccess, "putChunk failed: $putRes")
        assertTrue(sawChunkPath)
        assertEquals("aa", sawChunkHeader)
        val completeRes = repo.complete("up1")
        assertTrue(completeRes.isSuccess)
        val file: FileDto = completeRes.getOrNull()!!
        assertEquals("p1", file.id)
    }

    @Test
    fun logoutClearsStore() {
        val store = FakeTokenStore().apply {
            accessToken = "a"
            refreshToken = "r"
        }
        val repo = AuthRepository(ApiClient(store), store)
        repo.logout()
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
    }

    // ---- trash / favorite / archive / file versions ----

    @Test
    fun favoriteToggleOffIsSentInBody() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        var method: HttpMethod? = null
        var path: String? = null
        var body: String? = null
        val engine = MockEngine { req ->
            method = req.method
            path = req.url.encodedPath
            body = (req.body as? TextContent)?.text
            json(filePayload(isFavorite = false))
        }
        val res = ApiClient(store, engine).setFavorite("p1", favorite = false)
        assertTrue(res.isSuccess, "setFavorite failed: $res")
        assertEquals(HttpMethod.Put, method)
        assertEquals("/api/files/p1/favorite", path)
        // The client Json has encodeDefaults = false: if a false toggle were
        // dropped, un-favouriting would fail on the server side.
        assertTrue(
            body?.contains("\"favorite\":false") == true,
            "toggle-off payload missing, body was: $body",
        )
        assertFalse(res.getOrNull()!!.isFavorite)
    }

    @Test
    fun archiveToggleParsesUpdatedFile() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        var method: HttpMethod? = null
        var path: String? = null
        val engine = MockEngine { req ->
            method = req.method
            path = req.url.encodedPath
            json(filePayload(archivedAt = "2026-09-12T11:00:00Z"))
        }
        val repo = FilesRepository(ApiClient(store, engine))
        val res = repo.setArchived("p1", archived = true)
        assertTrue(res.isSuccess, "setArchived failed: $res")
        assertEquals(HttpMethod.Put, method)
        assertEquals("/api/files/p1/archive", path)
        assertEquals("2026-09-12T11:00:00Z", res.getOrNull()!!.archivedAt)
    }

    @Test
    fun forbiddenResponseMapsCodeAndStatus() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        val engine = MockEngine {
            json("""{"error":{"code":"FORBIDDEN","message":"not yours"}}""", HttpStatusCode.Forbidden)
        }
        val res = ApiClient(store, engine).setFavorite("p1", favorite = true)
        assertTrue(res.isFailure)
        val f = res.exceptionOrNull() as ApiFailure
        assertEquals("FORBIDDEN", f.code)
        assertEquals(403, f.httpStatus)
    }

    @Test
    fun trashListParsesResponse() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        var method: HttpMethod? = null
        var path: String? = null
        val engine = MockEngine { req ->
            method = req.method
            path = req.url.encodedPath
            json("""{"files":[${filePayload(deletedAt = "2026-09-12T12:00:00Z")}]}""")
        }
        val res = ApiClient(store, engine).trash()
        assertTrue(res.isSuccess, "trash failed: $res")
        assertEquals(HttpMethod.Get, method)
        assertEquals("/api/trash", path)
        val files = res.getOrNull()!!.files
        assertEquals(1, files.size)
        assertEquals("2026-09-12T12:00:00Z", files[0].deletedAt)
    }

    @Test
    fun restoreFromTrashMapsNameConflict() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        var method: HttpMethod? = null
        var path: String? = null
        val engine = MockEngine { req ->
            method = req.method
            path = req.url.encodedPath
            json("""{"error":{"code":"NAME_CONFLICT","message":"x"}}""", HttpStatusCode.Conflict)
        }
        val res = ApiClient(store, engine).restoreFromTrash("p1")
        assertTrue(res.isFailure)
        assertEquals(HttpMethod.Post, method)
        assertEquals("/api/trash/p1/restore", path)
        val f = res.exceptionOrNull() as ApiFailure
        assertEquals("NAME_CONFLICT", f.code)
        assertEquals(409, f.httpStatus)
    }

    @Test
    fun trashRemovalsAcceptNoContent() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        val paths = mutableListOf<String>()
        val methods = mutableListOf<HttpMethod>()
        val engine = MockEngine { req ->
            methods.add(req.method)
            paths.add(req.url.encodedPath)
            respond("", HttpStatusCode.NoContent)
        }
        val api = ApiClient(store, engine)
        assertTrue(api.deleteForever("p1").isSuccess, "deleteForever failed")
        assertTrue(api.emptyTrash().isSuccess, "emptyTrash failed")
        assertEquals(listOf("/api/trash/p1", "/api/trash"), paths)
        assertEquals(listOf(HttpMethod.Delete, HttpMethod.Delete), methods)
    }

    @Test
    fun fileVersionsReturnsListNewestFirst() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        var method: HttpMethod? = null
        var path: String? = null
        val engine = MockEngine { req ->
            method = req.method
            path = req.url.encodedPath
            json(
                """{"versions":[{"id":"v2","fileId":"p1","revision":2,"size":2048,"sha256":"bb",""" +
                    """"mimeType":"text/plain","createdAt":"c"},""" +
                    """{"id":"v1","fileId":"p1","revision":1,"size":1024,"sha256":"aa",""" +
                    """"mimeType":null,"createdAt":"c"}]}"""
            )
        }
        val res = ApiClient(store, engine).fileVersions("p1")
        assertTrue(res.isSuccess, "fileVersions failed: $res")
        assertEquals(HttpMethod.Get, method)
        assertEquals("/api/files/p1/versions", path)
        val versions = res.getOrNull()!!
        assertEquals(listOf("v2", "v1"), versions.map { it.id })
        assertEquals(listOf(2L, 1L), versions.map { it.revision })
    }

    @Test
    fun fileVersionsNotFoundMapsFailure() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        val engine = MockEngine {
            json("""{"error":{"code":"NOT_FOUND","message":"gone"}}""", HttpStatusCode.NotFound)
        }
        val res = ApiClient(store, engine).fileVersions("nope")
        assertTrue(res.isFailure)
        val f = res.exceptionOrNull() as ApiFailure
        assertEquals("NOT_FOUND", f.code)
        assertEquals(404, f.httpStatus)
    }

    @Test
    fun restoreAndDropFileVersionUseVersionRoutes() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        val paths = mutableListOf<String>()
        val methods = mutableListOf<HttpMethod>()
        val engine = MockEngine { req ->
            methods.add(req.method)
            paths.add(req.url.encodedPath)
            if (req.method == HttpMethod.Post) json(filePayload())
            else respond("", HttpStatusCode.NoContent)
        }
        val api = ApiClient(store, engine)
        val restored = api.restoreFileVersion("p1", "v2")
        assertTrue(restored.isSuccess, "restoreFileVersion failed: $restored")
        assertEquals("p1", restored.getOrNull()!!.id)
        assertTrue(api.deleteFileVersion("p1", "v1").isSuccess, "deleteFileVersion failed")
        assertEquals(
            listOf("/api/files/p1/versions/v2/restore", "/api/files/p1/versions/v1"),
            paths,
        )
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Delete), methods)
    }

    @Test
    fun uploadCompleteUnwrapsFilePayload() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        var method: HttpMethod? = null
        var path: String? = null
        val engine = MockEngine { req ->
            method = req.method
            path = req.url.encodedPath
            json("""{"file":${filePayload()},"replacedVersion":null}""")
        }
        val res = ApiClient(store, engine).uploadComplete("u1")
        assertTrue(res.isSuccess, "uploadComplete failed: $res")
        assertEquals(HttpMethod.Post, method)
        assertEquals("/api/uploads/u1/complete", path)
        assertEquals("p1", res.getOrNull()!!.id)
    }

    private fun filePayload(
        isFavorite: Boolean = false,
        archivedAt: String? = null,
        deletedAt: String? = null,
    ): String =
        """{"id":"p1","name":"a.bin","folderId":null,"size":4,"mimeType":"text/plain",""" +
            """"sha256":"aa","createdAt":"c","updatedAt":"u","isFavorite":$isFavorite,""" +
            """"archivedAt":${if (archivedAt == null) "null" else "\"" + archivedAt + "\""},""" +
            """"deletedAt":${if (deletedAt == null) "null" else "\"" + deletedAt + "\""}}"""
}
