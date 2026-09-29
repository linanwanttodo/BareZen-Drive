package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.db.BlobDeleteQueueTable
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.files.enqueue
import com.linan.barezen_drive.files.processQueue
import com.linan.barezen_drive.storage.LocalStorageProvider
import com.linan.barezen_drive.storage.StorageProvider
import com.linan.barezen_drive.storage.thumbKey
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A blob whose last reference disappeared used to be unlinked the moment a
 * recount said "nobody points at it any more". The recount's commit and the
 * unlink are two separate steps, and a concurrent dedup upload - the one that
 * calls `exists()`, sees the bytes, and commits a new reference a moment later -
 * could slip in between: its new row then points at a blob that is already
 * gone. Data loss, not a disk-space problem.
 *
 * The fix is a grace period. A key that loses its last reference is *queued*;
 * a later sweep re-counts it and only then unlinks. A dedup upload that lands
 * inside the window drops the queue entry, and the sweep's recount is the real
 * backstop. Deleting a blob becomes a decision that is allowed to be
 * re-decided, instead of a one-shot verdict.
 *
 * These tests pin that on H2, where the advisory lock both sides take is a
 * no-op: the queue, the grace window, the re-count and the resurrection. The
 * lock itself is PostgreSQL-only and covered by the end-to-end run against the
 * real database.
 */
class BlobGracePeriodTest {
    private val storageDir = Files.createTempDirectory("bz-grace").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var storage: StorageProvider

    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
    )

    private suspend fun ApplicationTestBuilder.setup(): String {
        val c = cfg()
        storage = LocalStorageProvider(java.nio.file.Path.of(c.storageDir))
        application { module(c, storage) }
        client.get("/health")
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"grace","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"grace","password":"password123"}""")
        }
        return "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    private fun sha256hex(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Uploads [name] and returns (fileId, storageKey, sha256). */
    private suspend fun ApplicationTestBuilder.upload(auth: String, name: String): Triple<String, String, String> {
        val body = name.encodeToByteArray()
        val sha = sha256hex(body)
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"sha256":"$sha","mimeType":"text/plain"}""")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val parsed = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        val fileId = if (!parsed.instantUpload) {
            client.put("/api/uploads/${parsed.uploadId}/chunks/0") {
                header(HttpHeaders.Authorization, auth)
                setBody(body)
            }
            val done = client.post("/api/uploads/${parsed.uploadId}/complete") {
                header(HttpHeaders.Authorization, auth)
            }
            json.decodeFromString<UploadCompleteResponse>(done.bodyAsText()).file.id
        } else {
            json.decodeFromString<UploadCompleteResponse>(init.bodyAsText()).file.id
        }
        val key = transaction(DatabaseFactory.db) {
            FilesTable.selectAll().where { FilesTable.id eq UUID.fromString(fileId) }
                .single()[FilesTable.storageKey]
        }
        return Triple(fileId, key, sha)
    }

    private suspend fun exists(key: String): Boolean = storage.exists(key)

    private fun queued(): Map<String, Long> = transaction(DatabaseFactory.db) {
        BlobDeleteQueueTable.selectAll().associate { it[BlobDeleteQueueTable.storageKey] to it[BlobDeleteQueueTable.queuedAt] }
    }

    @Test
    fun aTrashedFileKeepsItsBlobAndIsNotQueued() = testApplication {
        val auth = setup()
        val (id, key, _) = upload(auth, "grace-trash.txt")
        client.delete("/api/files/$id") { header(HttpHeaders.Authorization, auth) }

        assertTrue(runBlocking { exists(key) }, "a trashed file still owns its bytes (30-day retention)")
        assertFalse(key in queued().keys, "and must not be queued for deletion")
    }

    @Test
    fun aPermanentlyDeletedBlobIsQueuedNotUnlinked() = testApplication {
        val auth = setup()
        val (id, key, _) = upload(auth, "grace-a.txt")
        client.delete("/api/files/$id") { header(HttpHeaders.Authorization, auth) }
        // Out of the trash: past this point nothing references the blob.
        client.delete("/api/trash/$id") { header(HttpHeaders.Authorization, auth) }

        assertTrue(runBlocking { exists(key) }, "the bytes must survive the grace period")
        assertTrue(key in queued().keys, "the key must be on the delete queue")
    }

    @Test
    fun theQueueIsNotTouchedBeforeTheGracePeriodIsUp() = testApplication {
        val auth = setup()
        val (id, key, _) = upload(auth, "grace-b.txt")
        client.delete("/api/files/$id") { header(HttpHeaders.Authorization, auth) }
        client.delete("/api/trash/$id") { header(HttpHeaders.Authorization, auth) }
        val queuedAt = queued()[key] ?: error("not queued")

        processQueue(storage, graceMs = 3_600_000, now = queuedAt + 1000)

        assertTrue(runBlocking { exists(key) }, "inside the grace window nothing is unlinked")
        assertTrue(key in queued().keys, "and the queue entry stays for the next sweep")
    }

    @Test
    fun anExpiredQueueEntryIsUnlinkedAndDropped() = testApplication {
        val auth = setup()
        val (id, key, sha) = upload(auth, "grace-c.txt")
        client.delete("/api/files/$id") { header(HttpHeaders.Authorization, auth) }
        client.delete("/api/trash/$id") { header(HttpHeaders.Authorization, auth) }
        val queuedAt = queued()[key] ?: error("not queued")

        val removed = processQueue(storage, graceMs = 3_600_000, now = queuedAt + 3_600_001)

        assertEquals(1, removed, "the sweep reports what it unlinked")
        assertFalse(runBlocking { exists(key) }, "an expired orphan must finally be unlinked")
        assertFalse(runBlocking { storage.exists(thumbKey(sha)) }, "and so must its cover")
        assertTrue(queued().isEmpty(), "the queue entry is consumed")
    }

    @Test
    fun aKeyThatGainedAReferenceAgainIsKept() = testApplication {
        val auth = setup()
        val (id, key, sha) = upload(auth, "grace-d.txt")
        client.delete("/api/files/$id") { header(HttpHeaders.Authorization, auth) }
        client.delete("/api/trash/$id") { header(HttpHeaders.Authorization, auth) }
        val queuedAt = queued()[key] ?: error("not queued")

        // A dedup upload of the same content inside the window: exists() passes
        // (the bytes are still there), a new reference commits, and the sweep
        // must leave the blob alone.
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"grace-d-again.txt","size":13,"sha256":"$sha","mimeType":"text/plain"}""")
        }
        val parsed = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        if (!parsed.instantUpload) {
            client.put("/api/uploads/${parsed.uploadId}/chunks/0") {
                header(HttpHeaders.Authorization, auth)
                setBody("grace-d.txt".encodeToByteArray())
            }
            client.post("/api/uploads/${parsed.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        }

        processQueue(storage, graceMs = 3_600_000, now = queuedAt + 3_600_001)

        assertTrue(runBlocking { exists(key) }, "a re-referenced blob must survive the sweep")
        assertFalse(
            key in queued().keys,
            "and it must leave the queue - later sweeps must not keep re-checking it",
        )
    }

    @Test
    fun queueingTheSameKeyTwiceKeepsTheOriginalTimestamp() = testApplication {
        val auth = setup()
        val (id, key, _) = upload(auth, "grace-e.txt")
        client.delete("/api/files/$id") { header(HttpHeaders.Authorization, auth) }
        client.delete("/api/trash/$id") { header(HttpHeaders.Authorization, auth) }
        val first = queued()[key] ?: error("not queued")

        // Two delete paths racing would both enqueue; the second must not restart
        // the clock, or the blob would never be swept.
        transaction(DatabaseFactory.db) { enqueue(listOf(key), now = first + 60_000) }

        assertEquals(first, queued()[key], "re-queueing must not restart the grace period")
    }

    @Test
    fun aStillReferencedKeyIsNeverQueued() = testApplication {
        val auth = setup()
        val (_, key, _) = upload(auth, "grace-f.txt")

        // Someone hands the purge a key that is very much alive (a delete that
        // was rolled back, a version snapshot that still points at it).
        transaction(DatabaseFactory.db) { enqueue(listOf(key)) }

        assertFalse(key in queued().keys, "the recount must stop a live key from being queued")
    }
}
