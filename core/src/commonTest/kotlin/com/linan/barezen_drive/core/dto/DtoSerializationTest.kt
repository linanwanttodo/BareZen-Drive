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

    /**
     * The app client configures Json with encodeDefaults = false. A toggle
     * payload of false must still reach the server, because
     * FavoriteRequest.favorite, ArchiveRequest.archived and
     * RegistrationSettingRequest.open are required fields there: an omitted
     * field fails deserialization, so un-favoriting, un-archiving and closing
     * registration would all break.
     */
    @Test
    fun falseTogglesAreStillEncodedWithoutEncodeDefaults() {
        val wire = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        assertEquals("""{"favorite":false}""", wire.encodeToString(FavoriteRequest(false)))
        assertEquals("""{"archived":false}""", wire.encodeToString(ArchiveRequest(false)))
        assertEquals("""{"open":false}""", wire.encodeToString(RegistrationSettingRequest(false)))
    }

    /** A payload from an older server (no thumb/EXIF/favorite/archive columns) still parses. */
    @Test
    fun fileDtoAcceptsLegacyPayload() {
        val f = json.decodeFromString<FileDto>(
            """{"id":"p1","name":"a.bin","folderId":null,"size":4,"mimeType":null,""" +
                """"sha256":"aa","createdAt":"c","updatedAt":"u"}"""
        )
        assertFalse(f.hasThumbnail)
        assertFalse(f.isFavorite)
        assertNull(f.takenAt)
        assertNull(f.archivedAt)
        assertNull(f.deletedAt)
    }

    @Test
    fun fileDtoParsesNewFields() {
        val f = json.decodeFromString<FileDto>(
            """{"id":"p1","name":"a.jpg","folderId":"f1","size":4,"mimeType":"image/jpeg",""" +
                """"sha256":"aa","createdAt":"c","updatedAt":"u","hasThumbnail":true,""" +
                """"takenAt":"2026-09-12T10:00:00Z","isFavorite":true,""" +
                """"archivedAt":"2026-09-12T11:00:00Z","deletedAt":"2026-09-12T12:00:00Z"}"""
        )
        assertTrue(f.hasThumbnail)
        assertTrue(f.isFavorite)
        assertEquals("2026-09-12T10:00:00Z", f.takenAt)
        assertEquals("2026-09-12T11:00:00Z", f.archivedAt)
        assertEquals("2026-09-12T12:00:00Z", f.deletedAt)
    }

    /** complete now returns a wrapper; a payload without replacedVersion still parses. */
    @Test
    fun uploadCompleteResponseParsesWithoutReplacedVersion() {
        val r = json.decodeFromString<UploadCompleteResponse>(
            """{"file":{"id":"p1","name":"a.bin","folderId":null,"size":4,"mimeType":null,""" +
                """"sha256":"aa","createdAt":"c","updatedAt":"u"}}"""
        )
        assertEquals("p1", r.file.id)
        assertNull(r.replacedVersion)
    }

    @Test
    fun fileVersionsRoundTrip() {
        val r = json.decodeFromString<FileVersionsResponse>(
            """{"versions":[{"id":"v2","fileId":"p1","revision":2,"size":2048,"sha256":"bb",""" +
                """"mimeType":"text/plain","takenAt":null,"createdAt":"c"},""" +
                """{"id":"v1","fileId":"p1","revision":1,"size":1024,"sha256":"aa",""" +
                """"mimeType":null,"createdAt":"c"}]}"""
        )
        assertEquals(listOf(2L, 1L), r.versions.map { it.revision })
        assertEquals("v2", r.versions[0].id)
        assertNull(r.versions[1].mimeType)
    }
}
