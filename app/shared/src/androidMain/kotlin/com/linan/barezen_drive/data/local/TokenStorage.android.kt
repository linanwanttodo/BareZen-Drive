package com.linan.barezen_drive.data.local

import android.content.Context
import com.linan.barezen_drive.AndroidContext

/**
 * Plain SharedPreferences instead of EncryptedSharedPreferences: the Jetpack
 * security-crypto stack (deprecated upstream) can hang forever during
 * MasterKey/AndroidKeyStore initialization on some images - notably right on
 * the login request path, where the bearer loadTokens callback reads the
 * store before the request is sent. The symptom was a login button stuck on
 * "please wait" with the request never leaving the device. Tokens are per-device,
 * self-hosted session credentials; plain prefs is the pragmatic tradeoff.
 */
actual object TokenStorage : TokenStore {
    private val prefs by lazy {
        AndroidContext.app.getSharedPreferences("barezen_prefs", Context.MODE_PRIVATE)
    }

    actual override var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(v) = prefs.edit().putString("base_url", v).apply()

    actual override var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(v) = prefs.edit().putString("access_token", v).apply()

    actual override var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(v) = prefs.edit().putString("refresh_token", v).apply()

    actual override fun clear() {
        prefs.edit().clear().apply()
    }
}
