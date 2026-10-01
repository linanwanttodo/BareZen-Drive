package com.linan.barezen_drive

import com.linan.barezen_drive.core.dto.WebdavTokenCreatedResponse
import com.linan.barezen_drive.core.dto.WebdavTokenCreateRequest
import com.linan.barezen_drive.core.dto.WebdavTokensResponse
import com.linan.barezen_drive.data.api.ApiClient
import com.linan.barezen_drive.data.api.ApiFailure
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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(body, status, headersOf("Content-Type", "application/json"))

/**
 * The WebDAV app-password surface, from both ends.
 *
 * Decoding is asserted separately from the transport on purpose: the create
 * response is the single place the plaintext exists, so a field renamed or
 * dropped there silently removes the user's only chance to ever read their
 * mount password again, and no transport-level test would notice.
 */
class WebdavTokensDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ---- decoding ----

    @Test
    fun createdResponseCarriesPlaintextAlongsideTheToken() {
        val decoded = json.decodeFromString<WebdavTokenCreatedResponse>(
            """{"token":{"id":"t1","label":"iPad","readOnly":true,"createdAt":1758000000000,"lastUsedAt":0},""" +
                """"plaintext":"bzd_ab12cd34"}""",
        )
        assertEquals("bzd_ab12cd34", decoded.plaintext)
        assertEquals("t1", decoded.token.id)
        assertEquals("iPad", decoded.token.label)
        assertTrue(decoded.token.readOnly)
        assertEquals(1758000000000L, decoded.token.createdAt)
        // Never-used is 0 rather than null on purpose: the settings row turns it
        // into "never used" instead of printing a 1970 date.
        assertEquals(0L, decoded.token.lastUsedAt)
    }

    @Test
    fun thePlaintextDoesNotSurviveReencodingOfTheToken() {
        // The secret lives only in the outer field. A round-trip of the inner
        // token must not smuggle it out: the list endpoint answers with bare
        // tokens, and hashed storage is the only reason a leaked mount password
        // cannot be recovered.
        val created = json.decodeFromString<WebdavTokenCreatedResponse>(
            """{"token":{"id":"t1","label":"phone","readOnly":false,"createdAt":1,"lastUsedAt":2},""" +
                """"plaintext":"secret"}""",
        )
        val reencoded = json.encodeToString(created.token)
        assertFalse(reencoded.contains("secret"), "token re-encoding leaked the plaintext: $reencoded")
    }

    @Test
    fun tokensResponseDecodesAnEmptyList() {
        assertEquals(0, json.decodeFromString<WebdavTokensResponse>("""{"tokens":[]}""").tokens.size)
    }

    /** A server that has never minted one may answer with the field omitted. */
    @Test
    fun tokensResponseTreatsAMissingFieldAsEmpty() {
        assertTrue(json.decodeFromString<WebdavTokensResponse>("""{}""").tokens.isEmpty())
    }

    @Test
    fun tokensResponseDecodesEveryFieldOfEveryToken() {
        val decoded = json.decodeFromString<WebdavTokensResponse>(
            """{"tokens":[""" +
                """{"id":"t2","label":"iPad","readOnly":true,"createdAt":1758100000000,"lastUsedAt":1758111111111},""" +
                """{"id":"t1","label":"phone","readOnly":false,"createdAt":1758000000000,"lastUsedAt":0}]}""",
        )
        assertEquals(listOf("t2", "t1"), decoded.tokens.map { it.id })
        assertTrue(decoded.tokens[0].readOnly)
        assertFalse(decoded.tokens[1].readOnly)
        assertEquals(1758111111111L, decoded.tokens[0].lastUsedAt)
        assertEquals(0L, decoded.tokens[1].lastUsedAt)
    }

    /**
     * The client Json uses encodeDefaults = false. A read-only mount is the
     * whole point of the flag, so `true` has to reach the wire; a dropped
     * `readOnly` would silently hand the user a full-access credential.
     */
    @Test
    fun readOnlyRequestEncodesTheFlagUnderEncodeDefaultsFalse() {
        val wire = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        assertEquals("""{"label":"iPad","readOnly":true}""", wire.encodeToString(WebdavTokenCreateRequest("iPad", true)))
    }

    // ---- transport ----

    @Test
    fun createWebdavTokenPostsToTheTokenRouteAndReturnsThePlaintext() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        var method: HttpMethod? = null
        var path: String? = null
        var body: String? = null
        val engine = MockEngine { req ->
            method = req.method
            path = req.url.encodedPath
            body = (req.body as? TextContent)?.text
            json(
                """{"token":{"id":"t1","label":"iPad","readOnly":true,"createdAt":1,"lastUsedAt":0},""" +
                    """"plaintext":"bzd_secret"}""",
                HttpStatusCode.Created,
            )
        }
        val res = FilesRepository(ApiClient(store, engine)).createWebdavToken("iPad", readOnly = true)
        assertTrue(res.isSuccess, "createWebdavToken failed: $res")
        assertEquals(HttpMethod.Post, method)
        assertEquals("/api/webdav/tokens", path)
        assertTrue(body?.contains("\"readOnly\":true") == true, "readOnly missing from body: $body")
        assertEquals("bzd_secret", res.getOrNull()!!.plaintext)
    }

    @Test
    fun listWebdavTokensKeepsTheServerOrder() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        var method: HttpMethod? = null
        var path: String? = null
        val engine = MockEngine { req ->
            method = req.method
            path = req.url.encodedPath
            json("""{"tokens":[{"id":"t2","label":"new","readOnly":true,"createdAt":2,"lastUsedAt":3}]}""")
        }
        val res = ApiClient(store, engine).listWebdavTokens()
        assertTrue(res.isSuccess, "listWebdavTokens failed: $res")
        assertEquals(HttpMethod.Get, method)
        assertEquals("/api/webdav/tokens", path)
        // The server already sorts newest first; re-sorting here would only be
        // able to disagree with it.
        assertEquals(listOf("t2"), res.getOrNull()!!.tokens.map { it.id })
    }

    @Test
    fun revokeWebdavTokenAcceptsNoContent() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        var method: HttpMethod? = null
        var path: String? = null
        val engine = MockEngine { req ->
            method = req.method
            path = req.url.encodedPath
            respond("", HttpStatusCode.NoContent)
        }
        val res = FilesRepository(ApiClient(store, engine)).revokeWebdavToken("t1")
        assertTrue(res.isSuccess, "revokeWebdavToken failed: $res")
        assertEquals(HttpMethod.Delete, method)
        assertEquals("/api/webdav/tokens/t1", path)
    }

    @Test
    fun revokeUnknownWebdavTokenMapsToNotFound() = runTest {
        val store = FakeTokenStore().apply { accessToken = "a" }
        val engine = MockEngine {
            json("""{"error":{"code":"NOT_FOUND","message":"应用密码不存在"}}""", HttpStatusCode.NotFound)
        }
        val res = ApiClient(store, engine).revokeWebdavToken("nope")
        assertTrue(res.isFailure)
        val f = res.exceptionOrNull() as ApiFailure
        assertEquals("NOT_FOUND", f.code)
        assertEquals(404, f.httpStatus)
    }
}
