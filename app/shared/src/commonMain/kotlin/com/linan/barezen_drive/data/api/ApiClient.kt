package com.linan.barezen_drive.data.api

import com.linan.barezen_drive.core.dto.AlbumPage
import com.linan.barezen_drive.core.dto.ContentsResponse
import com.linan.barezen_drive.core.dto.CreateFolderRequest
import com.linan.barezen_drive.core.dto.ErrorResponse
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.FileLinkResponse
import com.linan.barezen_drive.core.dto.ServerStatsDto
import com.linan.barezen_drive.core.dto.FolderDto
import com.linan.barezen_drive.core.dto.LoginRequest
import com.linan.barezen_drive.core.dto.LoginResponse
import com.linan.barezen_drive.core.dto.RefreshRequest
import com.linan.barezen_drive.core.dto.RefreshResponse
import com.linan.barezen_drive.core.dto.RegisterRequest
import com.linan.barezen_drive.core.dto.RenameFolderRequest
import com.linan.barezen_drive.core.dto.ShareCreateRequest
import com.linan.barezen_drive.core.dto.ShareDto
import com.linan.barezen_drive.core.dto.SharesResponse
import com.linan.barezen_drive.core.dto.SharedContentsResponse
import com.linan.barezen_drive.core.dto.SharedInfoResponse
import com.linan.barezen_drive.core.dto.UpdateFileRequest
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.RecentFilesResponse
import com.linan.barezen_drive.core.dto.UploadInitRequest
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.core.dto.VersionInfoResponse
import com.linan.barezen_drive.core.dto.UserDto
import com.linan.barezen_drive.data.local.TokenStorage
import com.linan.barezen_drive.platform.monotonicNowMs
import com.linan.barezen_drive.data.local.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.ResponseException

import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.parameter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import com.linan.barezen_drive.i18n.I18n

/**
 * HTTP client for the BareZen-Drive server.
 *
 * baseUrl is read from the TokenStore on every call so the server address can
 * be entered at login time without rebuilding the client. The TokenStore is
 * injected (production wiring passes the TokenStorage expect object; tests
 * pass an in-memory fake).
 *
 * Bearer tokens auto-refresh on 401 via the ktor Auth plugin:
 * - loadTokens reads the current access token from the store (cacheTokens is
 *   disabled so the store stays the single source of truth).
 * - refreshTokens POSTs /api/auth/refresh with the stored refresh token using
 *   markAsRefreshTokenRequest (circuit breaker) so the refresh call itself
 *   can never recurse. On success the store is updated; on failure the store
 *   is cleared and the original 401 surfaces.
 *
 * Error mapping: an HTTP error response with a parseable error envelope
 * becomes ApiFailure.Http with the server code; a 401 with no envelope and a
 * now-empty store (refresh failed / session gone) becomes ApiFailure
 * .Unauthorized; everything without an HTTP response becomes ApiFailure
 * .Network.
 */
class ApiClient(
    private val store: TokenStore = TokenStorage,
    engineOverride: HttpClientEngine? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    val baseUrl: String get() = store.baseUrl.trimEnd('/')

    private fun HttpClientConfig<*>.commonConfig() {
        expectSuccess = true
        // Fail fast instead of spinning the "please wait" state forever when the
        // host is wrong or unreachable (e.g. the emulator-only 10.0.2.2 typed
        // into a desktop browser).
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
        install(ContentNegotiation) { json(this@ApiClient.json) }
        install(Auth) {
            bearer {
                cacheTokens = false
                loadTokens {
                    store.accessToken?.let { BearerTokens(it, store.refreshToken ?: "") }
                }
                refreshTokens {
                    val rt = store.refreshToken
                    if (rt == null) {
                        null
                    } else {
                        runCatching {
                            client.post("$baseUrl/api/auth/refresh") {
                                markAsRefreshTokenRequest()
                                contentType(ContentType.Application.Json)
                                setBody(RefreshRequest(rt))
                            }.body<RefreshResponse>()
                        }.fold(
                            onSuccess = { r ->
                                store.accessToken = r.accessToken
                                store.refreshToken = r.refreshToken
                                BearerTokens(r.accessToken, r.refreshToken)
                            },
                            onFailure = { e ->
                                if (e is CancellationException) throw e
                                store.clear()
                                null
                            },
                        )
                    }
                }
            }
        }
    }

    private val http: HttpClient = engineOverride
        ?.let { engine -> HttpClient(engine) { commonConfig() } }
        ?: HttpClient { commonConfig() }

    fun close() {
        http.close()
    }

    private suspend fun <T> runApi(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: ResponseException) {
            Result.failure(e.toApiFailure())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val msg = when {
                e is ConnectTimeoutException || e is SocketTimeoutException || e::class.simpleName == "HttpRequestTimeoutException" ->
                    I18n.strings.networkTimeout
                e.message?.contains("Failed to connect", ignoreCase = true) == true ->
                    I18n.strings.networkCannotConnect
                else -> e.message ?: e::class.simpleName ?: "Network error"
            }
            Result.failure(ApiFailure.Network(msg))
        }

    private suspend fun ResponseException.toApiFailure(): ApiFailure {
        val status = response.status
        val envelope = runCatching { response.bodyAsText() }
            .getOrNull()
            ?.let { text -> runCatching { json.decodeFromString<ErrorResponse>(text) }.getOrNull() }
        if (envelope != null) {
            return ApiFailure.Http(envelope.error.code, envelope.error.message, status.value)
        }
        if (status.value == 401 && store.accessToken == null && store.refreshToken == null) {
            return ApiFailure.Unauthorized("Session expired")
        }
        return ApiFailure.Http(null, status.description, status.value)
    }

    suspend fun register(username: String, password: String): Result<Unit> = runApi {
        http.post("$baseUrl/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(username, password))
        }
        Unit
    }

    suspend fun login(username: String, password: String): Result<LoginResponse> = runApi {
        http.post("$baseUrl/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }.body()
    }

    suspend fun me(): Result<UserDto> = runApi {
        http.get("$baseUrl/api/me").body()
    }

    suspend fun contents(folderId: String): Result<ContentsResponse> = runApi {
        http.get("$baseUrl/api/folders/$folderId/contents").body()
    }

    suspend fun recentFiles(limit: Int = 12): Result<RecentFilesResponse> = runApi {
        http.get("$baseUrl/api/files/recent") {
            parameter("limit", limit)
        }.body()
    }

    suspend fun createFolder(parentId: String?, name: String): Result<FolderDto> = runApi {
        http.post("$baseUrl/api/folders") {
            contentType(ContentType.Application.Json)
            setBody(CreateFolderRequest(parentId, name))
        }.body()
    }

    suspend fun renameFolder(id: String, name: String): Result<FolderDto> = runApi {
        http.patch("$baseUrl/api/folders/$id") {
            contentType(ContentType.Application.Json)
            setBody(RenameFolderRequest(name))
        }.body()
    }

    suspend fun deleteFolder(id: String): Result<Unit> = runApi {
        http.delete("$baseUrl/api/folders/$id")
        Unit
    }

    suspend fun updateFile(id: String, name: String?, folderId: String?): Result<FileDto> = runApi {
        http.patch("$baseUrl/api/files/$id") {
            contentType(ContentType.Application.Json)
            setBody(UpdateFileRequest(name, folderId))
        }.body()
    }

    suspend fun deleteFile(id: String): Result<Unit> = runApi {
        http.delete("$baseUrl/api/files/$id")
        Unit
    }

    suspend fun uploadInit(req: UploadInitRequest): Result<UploadInitResponse> = runApi {
        http.post("$baseUrl/api/uploads/init") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()
    }

    suspend fun uploadChunk(id: String, index: Int, bytes: ByteArray, sha: String?): Result<Unit> = runApi {
        http.put("$baseUrl/api/uploads/$id/chunks/$index") {
            setBody(bytes)
            sha?.let { header("X-Chunk-Sha256", it) }
        }
        Unit
    }

    suspend fun uploadComplete(id: String): Result<FileDto> = runApi {
        http.post("$baseUrl/api/uploads/$id/complete").body<UploadCompleteResponse>().file
    }

    suspend fun uploadAbort(id: String): Result<Unit> = runApi {
        http.delete("$baseUrl/api/uploads/$id")
        Unit
    }

    suspend fun putThumbnail(id: String, bytes: ByteArray): Result<Unit> = runApi {
        http.put("$baseUrl/api/files/$id/thumbnail") {
            contentType(ContentType.Image.JPEG)
            setBody(bytes)
        }
        Unit
    }

    suspend fun thumbnailBytes(id: String): Result<ByteArray> = runApi {
        http.get("$baseUrl/api/files/$id/thumbnail").bodyAsBytes()
    }

    suspend fun fileLink(id: String, ttl: Int? = null): Result<FileLinkResponse> = runApi {
        http.get("$baseUrl/api/files/$id/link") {
            ttl?.let { parameter("ttl", it) }
        }.body()
    }

    suspend fun album(limit: Int = 200, before: String? = null, root: String? = null): Result<AlbumPage> = runApi {
        http.get("$baseUrl/api/album") {
            parameter("limit", limit)
            before?.let { parameter("before", it) }
            root?.let { parameter("root", it) }
        }.body()
    }

    suspend fun serverStats(): Result<ServerStatsDto> = runApi {
        http.get("$baseUrl/api/server/stats").body()
    }

    /**
     * Build/update metadata of the connected server (GET /api/version, public).
     * Driven by the server so every client - including ones on networks that
     * cannot reach GitHub directly - gets the same answer.
     */
    suspend fun versionInfo(): Result<VersionInfoResponse> = runApi {
        http.get("$baseUrl/api/version").body()
    }

    /** Round-trip latency to /health in ms (monotonic clock). */
    suspend fun ping(): Long {
        val start = monotonicNowMs()
        http.get("$baseUrl/health").bodyAsText()
        return monotonicNowMs() - start
    }

    suspend fun download(id: String, range: LongRange? = null): ByteReadChannel =
        http.get("$baseUrl/api/files/$id/content") {
            range?.let { header(HttpHeaders.Range, "bytes=${it.first}-${it.last}") }
        }.bodyAsChannel()

    suspend fun previewBytes(id: String): ByteArray =
        http.get("$baseUrl/api/files/$id/content").bodyAsBytes()

    // ---- Share links (owner management, authenticated) ----

    suspend fun createShare(fileId: String? = null, folderId: String? = null, expiresInHours: Long? = null): Result<ShareDto> = runApi {
        http.post("$baseUrl/api/shares") {
            contentType(ContentType.Application.Json)
            setBody(ShareCreateRequest(fileId, folderId, expiresInHours))
        }.body()
    }

    suspend fun listShares(fileId: String? = null, folderId: String? = null): Result<SharesResponse> = runApi {
        http.get("$baseUrl/api/shares") {
            fileId?.let { parameter("fileId", it) }
            folderId?.let { parameter("folderId", it) }
        }.body()
    }

    suspend fun revokeShare(id: String): Result<Unit> = runApi {
        http.delete("$baseUrl/api/shares/$id")
        Unit
    }

    // ---- Public share endpoints (no auth; Bearer stays absent when no token) ----

    suspend fun sharedInfo(token: String): Result<SharedInfoResponse> = runApi {
        http.get("$baseUrl/api/public/shares/$token").body()
    }

    suspend fun sharedContents(token: String, folderId: String? = null): Result<SharedContentsResponse> = runApi {
        http.get("$baseUrl/api/public/shares/$token/contents") {
            folderId?.let { parameter("folder", it) }
        }.body()
    }

    /** Streamed download channel through a public share. */
    suspend fun sharedDownload(token: String, fileId: String, range: LongRange? = null): ByteReadChannel =
        http.get("$baseUrl/api/public/shares/$token/files/$fileId/content") {
            range?.let { header(HttpHeaders.Range, "bytes=${it.first}-${it.last}") }
        }.bodyAsChannel()

    /** Whole-body buffered read through a public share (image preview use case). */
    suspend fun sharedPreviewBytes(token: String, fileId: String): ByteArray =
        http.get("$baseUrl/api/public/shares/$token/files/$fileId/content").bodyAsBytes()

    suspend fun sharedThumbnailBytes(token: String, fileId: String): Result<ByteArray> = runApi {
        http.get("$baseUrl/api/public/shares/$token/files/$fileId/thumbnail").bodyAsBytes()
    }

    /** Absolute URL for browser/media-player consumption of a shared file. */
    fun sharedContentUrl(token: String, fileId: String): String = "$baseUrl/api/public/shares/$token/files/$fileId/content"
}
