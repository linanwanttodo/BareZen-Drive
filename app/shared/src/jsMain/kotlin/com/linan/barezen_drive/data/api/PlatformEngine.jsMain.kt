package com.linan.barezen_drive.data.api

import io.ktor.client.engine.HttpClientEngine

/**
 * Keep the platform default engine: the platform's own HTTP cache already
 * honours the immutable cache headers the server sends for covers (browser
 * cache on web/js, URLSession on iOS, and the JVM default engine caches too).
 */
actual fun platformHttpEngine(): HttpClientEngine? = null
