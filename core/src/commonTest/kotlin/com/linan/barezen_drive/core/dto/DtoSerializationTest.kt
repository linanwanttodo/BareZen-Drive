package com.linan.barezen_drive.core.dto

import kotlinx.serialization.json.Json
import kotlin.test.*

class DtoSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun fileDtoRoundTrip() {
        val f = FileDto("id1", "a.jpg", "fold1", 123L, "image/jpeg", "ab".repeat(32), "2026-09-02T00:00:00Z", "2026-09-02T00:00:00Z")
        assertEquals(f, json.decodeFromString(json.encodeToString(f)))
    }

    @Test
    fun uploadInitResponseDefaults() {
        val decoded = json.decodeFromString<UploadInitResponse>("""{"uploadId":"u1","chunkSize":5242880,"receivedChunks":[0,2]}""")
        assertFalse(decoded.instantUpload); assertEquals(null, decoded.file); assertEquals(listOf(0, 2), decoded.receivedChunks)
    }

    @Test
    fun errorEnvelopeShape() {
        val e = json.decodeFromString<ErrorResponse>("""{"error":{"code":"NAME_CONFLICT","message":"x"}}""")
        assertEquals(ErrorCodes.NAME_CONFLICT, e.error.code)
    }
}
