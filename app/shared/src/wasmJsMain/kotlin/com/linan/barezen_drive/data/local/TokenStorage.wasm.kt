package com.linan.barezen_drive.data.local

import kotlinx.browser.window

actual object TokenStorage : TokenStore {
    private const val KEY_BASE_URL = "bz_base_url"
    private const val KEY_ACCESS_TOKEN = "bz_access_token"
    private const val KEY_REFRESH_TOKEN = "bz_refresh_token"

    private fun get(k: String): String? = window.localStorage.getItem(k)

    private fun set(k: String, v: String?) {
        if (v == null) window.localStorage.removeItem(k) else window.localStorage.setItem(k, v)
    }

    actual override var baseUrl: String
        get() = get(KEY_BASE_URL) ?: ""
        set(v) = set(KEY_BASE_URL, v)

    actual override var accessToken: String?
        get() = get(KEY_ACCESS_TOKEN)
        set(v) = set(KEY_ACCESS_TOKEN, v)

    actual override var refreshToken: String?
        get() = get(KEY_REFRESH_TOKEN)
        set(v) = set(KEY_REFRESH_TOKEN, v)

    actual override fun clear() {
        accessToken = null
        refreshToken = null
        baseUrl = ""
    }
}
