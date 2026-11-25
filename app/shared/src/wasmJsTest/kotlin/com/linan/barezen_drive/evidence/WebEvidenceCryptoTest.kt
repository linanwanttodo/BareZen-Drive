package com.linan.barezen_drive.evidence

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebEvidenceCryptoTest {
    @Test
    fun derivedManifestKeyVerifiesHmacSha256() = runTest {
        val keys = deriveWebVaultKeys(
            "correct horse battery staple",
            ByteArray(32) { it.toByte() },
            iterations = 1_000,
        )
        val tag = "6ea7d22271634a0e7b9a028368ae5905d811348c212520b560d560532125de9c".hexToBytes()
        assertTrue(webVerifyManifestTag(keys.manifestKey, "evidence".encodeToByteArray(), tag))
        assertFalse(
            webVerifyManifestTag(
                keys.manifestKey,
                "tampered".encodeToByteArray(),
                tag,
            ),
        )
    }
}
