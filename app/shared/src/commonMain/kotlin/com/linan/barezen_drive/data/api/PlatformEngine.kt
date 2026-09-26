package com.linan.barezen_drive.data.api

import io.ktor.client.engine.HttpClientEngine

/**
 * The HTTP engine the app's clients should use, or null to keep the platform
 * default.
 *
 * Covers and thumbnails are served with `Cache-Control: public,
 * max-age=31536000, immutable` precisely so they can be cached forever, and on
 * web that already happens: the browser's own HTTP cache answers the second
 * visit, so a fresh page context re-requests nothing (measured - the album
 * reload issued 0 thumbnail requests).
 *
 * Android is the platform that needs help. The default OkHttp client Ktor
 * builds has `cache = null`, so every cold start re-downloaded every visible
 * cover over the mobile link, and after any memory pressure the same happened
 * again mid-session. Handing the engine a real cache directory fixes both
 * without a line of app-level cache code.
 */
expect fun platformHttpEngine(): HttpClientEngine?
