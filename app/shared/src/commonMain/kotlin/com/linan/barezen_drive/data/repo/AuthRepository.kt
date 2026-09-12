package com.linan.barezen_drive.data.repo

import com.linan.barezen_drive.core.dto.UserDto
import com.linan.barezen_drive.data.api.ApiClient
import com.linan.barezen_drive.data.local.TokenStore

/**
 * Auth flows on top of ApiClient. login/register take the server host so the
 * base URL can be entered at login time; tokens are persisted through the
 * injected TokenStore (production: TokenStorage; tests: in-memory fake).
 */
class AuthRepository(
    private val api: ApiClient,
    private val store: TokenStore,
) {
    suspend fun register(host: String, username: String, password: String): Result<Unit> {
        store.baseUrl = normalizeHost(host)
        return api.register(username, password)
    }

    suspend fun login(host: String, username: String, password: String): Result<UserDto> {
        store.baseUrl = normalizeHost(host)
        return api.login(username, password).mapCatching { r ->
            store.accessToken = r.accessToken
            store.refreshToken = r.refreshToken
            r.user
        }
    }

    fun defaultHost(): String = store.baseUrl

    /** Whether the server accepts new sign-ups; true when it cannot be reached. */
    suspend fun registrationStatus(host: String): Result<Boolean> {
        if (host.isNotBlank()) store.baseUrl = normalizeHost(host)
        return api.registrationStatus().map { it.open }
    }

    fun logout() {
        store.accessToken = null
        store.refreshToken = null
    }

    private companion object {
        /** Blank-safe host normalization: adds http:// when the user typed a bare host. */
        fun normalizeHost(host: String): String {
            val trimmed = host.trim().trimEnd('/')
            if (trimmed.isBlank()) return trimmed
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
        }
    }
}
