package com.linan.barezen_drive.evidence

import android.content.Context
import com.linan.barezen_drive.AndroidContext
import com.linan.barezen_drive.data.local.TokenStorage

object EvidenceIdentity {
    private val prefs by lazy {
        AndroidContext.app.getSharedPreferences("barezen_evidence_identity", Context.MODE_PRIVATE)
    }

    fun cached(): Pair<String, String>? {
        val account = prefs.getString("account_id", null) ?: return null
        return serverKey() to account
    }

    fun save(serverKey: String, accountId: String) {
        prefs.edit().putString("server_key", serverKey).putString("account_id", accountId).apply()
    }

    fun serverKey(): String =
        EvidenceCrypto.sha256(TokenStorage.baseUrl.trimEnd('/').encodeToByteArray()).toHex()
}
