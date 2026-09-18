package com.linan.barezen_drive.data.api

import com.linan.barezen_drive.core.dto.AlbumPage
import com.linan.barezen_drive.core.dto.ArchiveRequest
import com.linan.barezen_drive.core.dto.ContentsResponse
import com.linan.barezen_drive.core.dto.CreateFolderRequest
import com.linan.barezen_drive.core.dto.ErrorResponse
import com.linan.barezen_drive.core.dto.FavoriteRequest
import com.linan.barezen_drive.core.dto.FileDto
import com.linan.barezen_drive.core.dto.FileLinkResponse
import com.linan.barezen_drive.core.dto.FileVersionDto
import com.linan.barezen_drive.core.dto.FileVersionsResponse
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
import com.linan.barezen_drive.core.dto.TrashResponse
import com.linan.barezen_drive.core.dto.UpdateFileRequest
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.RecentFilesResponse
import com.linan.barezen_drive.core.dto.UploadInitRequest
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.core.dto.VersionInfoResponse
import com.linan.barezen_drive.core.dto.RegistrationSettingRequest
import com.linan.barezen_drive.core.dto.AdminUsersResponse
import com.linan.barezen_drive.core.dto.RegistrationStatusDto
import com.linan.barezen_drive.core.dto.UserDto
import com.linan.barezen_drive.data.local.TokenStorage
import com.linan.barezen_drive.platform.monotonicNowMs
import com.linan.barezen_drive.data.local.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import com.linan.barezen_drive.i18n.I18n
import kotlin.time.Duration.Companion.milliseconds

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

    /** Serializes refresh attempts across both HTTP clients (see refreshTokens). */
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()

    val baseUrl: String get() = store.baseUrl.trimEnd('/')

    private fun HttpClientConfig<*>.commonConfig(streaming: Boolean = false) {
        expectSuccess = true
        // Fail fast instead of spinning the "please wait" state forever when the
        // host is wrong or unreachable (e.g. the emulator-only 10.0.2.2 typed
        // into a desktop browser). For the upload/download client we drop the
        // total-request bound entirely: a 20 MiB chunk on a slow link, or a
        // multi-GB download, routinely blows past any fixed request timeout,
        // which used to abort the transfer and make the next pass restart from
        // scratch. The socket timeout still catches a genuinely dead connection.
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = if (streaming) HttpTimeoutConfig.INFINITE_TIMEOUT_MS else 20_000
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
                    // Access token as this request observed it, BEFORE the lock:
                    // the comparison below distinguishes "I am the first 401 to
                    // arrive" from "another waiter already refreshed for me".
                    val seenAccess = store.accessToken
                    // The JSON client and the streaming client share the token
                    // store but not the ktor Auth plugin, so both can hit a 401
                    // at the same moment. Refreshes are serialized here and the
                    // token is re-read under the lock: whoever comes second
                    // uses the freshly rolled token instead of racing the
                    // server with one that was already revoked (the server
                    // rotates refresh tokens one-shot, so a loser would get a
                    // 401 that looks exactly like a dead session).
                    refreshMutex.withLock {
                        // A waiter that finds the access token already changed
                        // reuses that result instead of rotating again. One
                        // burst of 401s used to churn one server-side rotation
                        // per waiter, which burned one-shot tokens and could
                        // hit the refresh rate limit.
                        val current = store.accessToken
                        val rt = store.refreshToken
                        when {
                            current == null && rt == null -> null
                            current != null && current != seenAccess -> BearerTokens(current, rt ?: "")
                            else -> runCatching {
                                client.post("$baseUrl/api/auth/refresh") {
                                    markAsRefreshTokenRequest()
                                    contentType(ContentType.Application.Json)
                                    setBody(RefreshRequest(rt ?: ""))
                                }.body<RefreshResponse>()
                            }.fold(
                                onSuccess = { r ->
                                    store.accessToken = r.accessToken
                                    store.refreshToken = r.refreshToken
                                    BearerTokens(r.accessToken, r.refreshToken)
                                },
                                onFailure = { e ->
                                    if (e is CancellationException) throw e
                                    // Only an explicit rejection from the refresh
                                    // endpoint ends the session. A 5xx or a
                                    // network failure (server restarting, mobile
                                    // link flapping) must keep the stored tokens:
                                    // the refresh token outlives the outage and
                                    // the session heals by itself once the server
                                    // answers again. Clearing on any HTTP error
                                    // used to sign the user out whenever the
                                    // server blipped. The server address is kept
                                    // either way so re-login is one step.
                                    val status = (e as? ResponseException)?.response?.status?.value
                                    if (status == 401 || status == 403) {
                                        store.accessToken = null
                                        store.refreshToken = null
                                    }
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private val http: HttpClient = engineOverride
        ?.let { engine -> HttpClient(engine) { commonConfig() } }
        ?: HttpClient { commonConfig() }

    // Separate, long-lived client for bulk byte transfer (upload chunks and
    // downloads) so they are never subject to the short request timeout the
    // JSON API uses. Token refresh is identical (same store), only the timeout
    // profile differs.
    private val stream: HttpClient = engineOverride
        ?.let { engine -> HttpClient(engine) { commonConfig(streaming = true) } }
        ?: HttpClient { commonConfig(streaming = true) }

    /**
     * Connect-level transient failures (server blip, mobile link handover)
     * are retried with backoff before surfacing. Only failures where the
     * connection itself never opened are replayed: the request cannot have
     * reached the server, so a retry is safe for every method, POST included.
     * A timeout after the exchange started is NOT retried - its outcome is
     * ambiguous and a replayed POST could duplicate a folder.
     */
    private val maxConnectAttempts = 3
    private val retryBackoffMs = longArrayOf(600, 1_500)

    private suspend fun <T> runApi(block: suspend () -> T): Result<T> {
        var attempt = 0
        while (true) {
            var failure: Exception? = null
            val result = try {
                Result.success(block())
            } catch (e: ResponseException) {
                failure = e
                Result.failure(e.toApiFailure())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure = e
                Result.failure(e.toNetworkFailure())
            }
            if (result.isSuccess) return result
            val retryable = failure is ConnectTimeoutException ||
                failure?.message?.contains("Failed to connect", ignoreCase = true) == true
            if (!retryable || attempt == maxConnectAttempts - 1) return result
            delay(retryBackoffMs[attempt].milliseconds)
            attempt++
        }
    }

    private fun Exception.toNetworkFailure(): ApiFailure {
        val msg = when {
            this is ConnectTimeoutException || this is SocketTimeoutException || this::class.simpleName == "HttpRequestTimeoutException" ->
                I18n.strings.networkTimeout
            message?.contains("Failed to connect", ignoreCase = true) == true ->
                I18n.strings.networkCannotConnect
            else -> message ?: this::class.simpleName ?: "Network error"
        }
        return ApiFailure.Network(msg)
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

    /** Name search across the account's files (search tab). */
    suspend fun search(query: String): Result<RecentFilesResponse> = runApi {
        http.get("$baseUrl/api/search") {
            parameter("q", query)
        }.body()
    }

    /** Uploads a new avatar (server square-crops and downscales). */
    suspend fun putAvatar(bytes: ByteArray): Result<Unit> = runApi {
        http.put("$baseUrl/api/me/avatar") {
            setBody(bytes)
            contentType(ContentType.Application.OctetStream)
        }
    }

    /** Fetches the avatar image; 404 (no avatar) becomes a failure result. */
    suspend fun avatarBytes(userId: String): Result<ByteArray> = runApi {
        http.get("$baseUrl/api/users/$userId/avatar").bodyAsBytes()
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
    }

    suspend fun updateFile(id: String, name: String?, folderId: String?): Result<FileDto> = runApi {
        http.patch("$baseUrl/api/files/$id") {
            contentType(ContentType.Application.Json)
            setBody(UpdateFileRequest(name, folderId))
        }.body()
    }

    suspend fun deleteFile(id: String): Result<Unit> = runApi {
        // Soft delete: the file lands in the trash and keeps its bytes.
        http.delete("$baseUrl/api/files/$id")
    }

    /** Favorites one file on or off (album "favorites" filter). */
    suspend fun setFavorite(id: String, favorite: Boolean): Result<FileDto> = runApi {
        http.put("$baseUrl/api/files/$id/favorite") {
            contentType(ContentType.Application.Json)
            setBody(FavoriteRequest(favorite))
        }.body()
    }

    /** Archives a file: hidden from the timeline and search until unarchived. */
    suspend fun setArchived(id: String, archived: Boolean): Result<FileDto> = runApi {
        http.put("$baseUrl/api/files/$id/archive") {
            contentType(ContentType.Application.Json)
            setBody(ArchiveRequest(archived))
        }.body()
    }

    // ---- Trash (soft-deleted files) ----

    /** Revision history of one file, newest first (set after an overwrite upload). */
    suspend fun fileVersions(id: String): Result<List<FileVersionDto>> = runApi {
        http.get("$baseUrl/api/files/$id/versions").body<FileVersionsResponse>().versions
    }

    /** Makes one revision the live content; the displaced head joins history. */
    suspend fun restoreFileVersion(id: String, versionId: String): Result<FileDto> = runApi {
        http.post("$baseUrl/api/files/$id/versions/$versionId/restore").body()
    }

    /** Drops a single revision (and its blob when nothing else references it). */
    suspend fun deleteFileVersion(id: String, versionId: String): Result<Unit> = runApi {
        http.delete("$baseUrl/api/files/$id/versions/$versionId")
    }

    suspend fun trash(): Result<TrashResponse> = runApi {
        http.get("$baseUrl/api/trash").body()
    }

    suspend fun restoreFromTrash(id: String): Result<FileDto> = runApi {
        http.post("$baseUrl/api/trash/$id/restore").body()
    }

    /** Permanent removal of one trashed file; a live id is refused by the server. */
    suspend fun deleteForever(id: String): Result<Unit> = runApi {
        http.delete("$baseUrl/api/trash/$id")
    }

    suspend fun emptyTrash(): Result<Unit> = runApi {
        http.delete("$baseUrl/api/trash")
    }

    suspend fun uploadInit(req: UploadInitRequest): Result<UploadInitResponse> = runApi {
        http.post("$baseUrl/api/uploads/init") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()
    }

    suspend fun uploadChunk(id: String, index: Int, bytes: ByteArray, sha: String?): Result<Unit> = runApi {
        stream.put("$baseUrl/api/uploads/$id/chunks/$index") {
            setBody(bytes)
            sha?.let { header("X-Chunk-Sha256", it) }
            // The dedicated streaming client has no request timeout: a 20 MiB
            // chunk on a slow link must not be aborted mid-flight (see commonConfig).
        }
    }

    suspend fun uploadComplete(id: String): Result<FileDto> = runApi {
        http.post("$baseUrl/api/uploads/$id/complete").body<UploadCompleteResponse>().file
    }

    suspend fun uploadAbort(id: String): Result<Unit> = runApi {
        http.delete("$baseUrl/api/uploads/$id")
    }

    suspend fun putThumbnail(id: String, bytes: ByteArray): Result<Unit> = runApi {
        http.put("$baseUrl/api/files/$id/thumbnail") {
            contentType(ContentType.Image.JPEG)
            setBody(bytes)
        }
    }

    suspend fun thumbnailBytes(id: String): Result<ByteArray> = runApi {
        http.get("$baseUrl/api/files/$id/thumbnail").bodyAsBytes()
    }

    suspend fun fileLink(id: String, ttl: Int? = null): Result<FileLinkResponse> = runApi {
        http.get("$baseUrl/api/files/$id/link") {
            ttl?.let { parameter("ttl", it) }
        }.body()
    }

    suspend fun album(limit: Int = 200, before: String? = null, root: String? = null, favorite: Boolean = false, archived: Boolean = false): Result<AlbumPage> = runApi {
        http.get("$baseUrl/api/album") {
            parameter("limit", limit)
            before?.let { parameter("before", it) }
            root?.let { parameter("root", it) }
            if (favorite) parameter("favorite", true)
            if (archived) parameter("archived", true)
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

    /** Owner: list registered accounts with their file counts. */
    suspend fun adminUsers(): Result<AdminUsersResponse> = runApi {
        http.get("$baseUrl/api/admin/users").body()
    }

    /** Owner: delete an account together with all of its data. */
    suspend fun adminDeleteUser(id: String): Result<Unit> = runApi {
        http.delete("$baseUrl/api/admin/users/$id")
    }

    /**
     * Whether the server accepts new sign-ups right now (public endpoint).
     * baseUrlOverride probes a typed-in host without touching the persisted
     * base URL - the login screen polls it while the server URL field is
     * being edited, and a probe must never overwrite the stored address.
     */
    suspend fun registrationStatus(baseUrlOverride: String? = null): Result<RegistrationStatusDto> = runApi {
        http.get("${baseUrlOverride?.trimEnd('/') ?: baseUrl}/api/settings/registration").body()
    }

    /** Owner toggle for open registration (PATCH /api/settings/registration). */
    suspend fun setRegistrationOpen(open: Boolean): Result<RegistrationStatusDto> = runApi {
        http.patch("$baseUrl/api/settings/registration") {
            contentType(ContentType.Application.Json)
            setBody(RegistrationSettingRequest(open))
        }.body()
    }

    /** Round-trip latency to /health in ms (monotonic clock). */
    suspend fun ping(): Long {
        val start = monotonicNowMs()
        http.get("$baseUrl/health").bodyAsText()
        return monotonicNowMs() - start
    }

    suspend fun download(id: String, range: LongRange? = null): ByteReadChannel =
        stream.get("$baseUrl/api/files/$id/content") {
            range?.let { header(HttpHeaders.Range, "bytes=${it.first}-${it.last}") }
        }.bodyAsChannel()

    suspend fun previewBytes(id: String): ByteArray =
        stream.get("$baseUrl/api/files/$id/content").bodyAsBytes()

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
        stream.get("$baseUrl/api/public/shares/$token/files/$fileId/content") {
            range?.let { header(HttpHeaders.Range, "bytes=${it.first}-${it.last}") }
        }.bodyAsChannel()

    /** Whole-body buffered read through a public share (image preview use case). */
    suspend fun sharedPreviewBytes(token: String, fileId: String): ByteArray =
        stream.get("$baseUrl/api/public/shares/$token/files/$fileId/content").bodyAsBytes()

}
