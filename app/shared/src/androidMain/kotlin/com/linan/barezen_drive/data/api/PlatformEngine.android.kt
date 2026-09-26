package com.linan.barezen_drive.data.api

import com.linan.barezen_drive.AndroidContext
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

private const val CACHE_BYTES = 64L * 1024 * 1024

actual fun platformHttpEngine(): HttpClientEngine? = runCatching {
    // cacheDir, not filesDir: this is disposable, regenerable data that the
    // system may reclaim under storage pressure - exactly what a cache is for.
    val dir = File(AndroidContext.app.cacheDir, "http").apply { mkdirs() }
    OkHttp.create {
        preconfigured = OkHttpClient.Builder()
            .cache(Cache(dir, CACHE_BYTES))
            .build()
    }
}.getOrNull()
