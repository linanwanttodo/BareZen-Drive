package com.linan.barezen_drive.data.api

import com.linan.barezen_drive.data.repo.FilesRepository

/**
 * The one API client and file repository of the process.
 *
 * Each `ApiClient` builds its own engines, and on Android an engine means an
 * OkHttp dispatcher, a connection pool with idle keep-alive sockets, and a 64 MB
 * response cache over `cacheDir/http`. Nothing disposes them - the class has no
 * close() - so constructing one per background pass leaked a pool per pass and
 * left several live `Cache` objects pointing at the same journal directory, a
 * setup OkHttp's disk cache does not support (one of the two silently stops
 * caching, which is the opposite of why the cache exists).
 *
 * Sharing one instance is safe because the client holds no per-screen state:
 * tokens come from the global [com.linan.barezen_drive.data.local.TokenStorage],
 * which is exactly what the UI and the sync workers already both read.
 */
object AppClient {
    val api: ApiClient by lazy { ApiClient() }
    val files: FilesRepository by lazy { FilesRepository(api) }
}
