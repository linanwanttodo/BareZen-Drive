package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.S3BucketsResponse
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.UploadSessionsTable
import com.linan.barezen_drive.db.UsersTable
import com.linan.barezen_drive.db.WebdavTokensTable
import com.linan.barezen_drive.s3.S3Exception
import com.linan.barezen_drive.s3.deleteEmptyS3Bucket
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class S3ConsoleTest {
    private val storageDir = Files.createTempDirectory("bz-s3console").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""

    private suspend fun ApplicationTestBuilder.setup() {
        val cfg = AppConfig(
            0,
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
            registrationOpen = true,
        )
        application { module(cfg, LocalStorageProvider(Path.of(cfg.storageDir))) }
        client.post("/api/auth/register") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"username":"user1","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""")
            .find(client.post("/api/auth/login") {
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"username":"user1","password":"password123"}""")
            }.bodyAsText())!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.buckets(): S3BucketsResponse {
        val resp = client.get("/api/s3/buckets") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return json.decodeFromString(resp.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.createFolder(name: String, parentId: String? = null): String {
        val parentJson = parentId?.let { """"parentId":"$it",""" } ?: ""
        val resp = client.post("/api/folders") {
            header(HttpHeaders.Authorization, auth)
            header(HttpHeaders.ContentType, "application/json")
            setBody("{$parentJson\"name\":\"$name\"}")
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return Regex(""""id":"([^"]+)"""").find(resp.bodyAsText())!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.uploadTo(folderId: String?, name: String, size: Int): String {
        val folderJson = folderId?.let { """"folderId":"$it",""" } ?: ""
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            header(HttpHeaders.ContentType, "application/json")
            setBody("{$folderJson\"name\":\"$name\",\"size\":$size}")
        }
        assertEquals(HttpStatusCode.OK, init.status, init.bodyAsText())
        val sid = Regex(""""uploadId":"([^"]+)"""").find(init.bodyAsText())!!.groupValues[1]
        client.put("/api/uploads/$sid/chunks/0") {
            header(HttpHeaders.Authorization, auth)
            setBody(ByteArray(size) { 0x41 })
        }
        val done = client.post("/api/uploads/$sid/complete") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
        return Regex(""""id":"([^"]+)"""").find(done.bodyAsText())!!.groupValues[1]
    }

    @Test
    fun emptyAccountReturnsNoBuckets() = testApplication {
        setup()
        val r = buckets()
        assertTrue(r.buckets.isEmpty())
        assertEquals(0L, r.rootFileCount)
    }

    @Test
    fun validAndInvalidTopLevelFoldersAreBothListed() = testApplication {
        setup()
        createFolder("photos")
        createFolder("我的文档")
        createFolder("UPPER")
        createFolder("ab")
        val r = buckets()
        assertEquals(4, r.buckets.size)
        val byName = r.buckets.associateBy { it.name }
        assertTrue(byName["photos"]!!.s3Valid)
        assertFalse(byName["我的文档"]!!.s3Valid)
        assertFalse(byName["UPPER"]!!.s3Valid)
        assertFalse(byName["ab"]!!.s3Valid)
    }

    @Test
    fun objectCountAndBytesAreRecursive() = testApplication {
        setup()
        val root = createFolder("data")
        val sub = createFolder("nested", root)
        uploadTo(root, "a.bin", 100)
        uploadTo(sub, "b.bin", 250)
        uploadTo(null, "loose.txt", 10)
        val r = buckets()
        val bucket = r.buckets.single { it.name == "data" }
        assertEquals(2L, bucket.objectCount)
        assertEquals(350L, bucket.totalBytes)
        assertEquals(1L, r.rootFileCount)
    }

    @Test
    fun trashedFilesDoNotCountTowardsBucketStats() = testApplication {
        setup()
        val root = createFolder("media")
        val fileId = uploadTo(root, "gone.bin", 500)
        uploadTo(root, "live.bin", 200)
        client.delete("/api/files/$fileId") { header(HttpHeaders.Authorization, auth) }
        val r = buckets()
        val bucket = r.buckets.single { it.name == "media" }
        assertEquals(1L, bucket.objectCount)
        assertEquals(200L, bucket.totalBytes)
    }

    @Test
    fun bucketsAreSortedByNameCaseInsensitive() = testApplication {
        setup()
        createFolder("zebra")
        createFolder("alpha")
        createFolder("Beta")
        val r = buckets()
        assertEquals(listOf("alpha", "Beta", "zebra"), r.buckets.map { it.name })
    }

    @Test
    fun reservedTopLevelFoldersAreNotS3Buckets() = testApplication {
        setup()
        createFolder("api")
        createFolder("dav")
        val r = buckets()
        assertTrue(r.buckets.none { it.s3Valid })
    }

    @Test
    fun managementCreateRejectsReservedBucketName() = testApplication {
        setup()
        val resp = client.post("/api/s3/buckets") {
            header(HttpHeaders.Authorization, auth)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"name":"api"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status, resp.bodyAsText())
        assertTrue(buckets().buckets.isEmpty())
    }

    @Test
    fun managementDeleteRejectsFileWrittenAfterListing() = testApplication {
        setup()
        val root = createFolder("data")
        assertEquals(0L, buckets().buckets.single { it.name == "data" }.objectCount)
        val fileId = uploadTo(root, "late.bin", 32)

        val resp = client.delete("/api/s3/buckets/$root") { header(HttpHeaders.Authorization, auth) }

        assertEquals(HttpStatusCode.Conflict, resp.status, resp.bodyAsText())
        val stillThere = transaction(DatabaseFactory.db) {
            FilesTable.selectAll().where { FilesTable.id eq UUID.fromString(fileId) }.any()
        }
        assertTrue(stillThere)
        assertTrue(buckets().buckets.any { it.folderId == root })
    }

    @Test
    fun managementDeleteRejectsFileCommittedWhileEmptinessCheckRuns() = testApplication {
        setup()
        val root = createFolder("data")
        val rootId = UUID.fromString(root)
        val userId = transaction(DatabaseFactory.db) {
            UsersTable.selectAll().single()[UsersTable.id]
        }
        val sessionId = UUID.randomUUID()
        transaction(DatabaseFactory.db) {
            UploadSessionsTable.insert {
                it[id] = sessionId
                it[UploadSessionsTable.user] = userId
                it[folder] = rootId
                it[name] = "finished.bin"
                it[size] = 0L
                it[chunkSize] = 1024L
                it[status] = "completed"
                it[expiresAt] = Long.MAX_VALUE
            }
        }

        val fileId = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(2)
        val folderLocked = CountDownLatch(1)
        val releaseUpload = CountDownLatch(1)
        try {
            val upload = pool.submit {
                transaction(DatabaseFactory.db) {
                    FoldersTable.selectAll().where { FoldersTable.id eq rootId }.forUpdate().single()
                    UploadSessionsTable.selectAll().where { UploadSessionsTable.id eq sessionId }.forUpdate().single()
                    folderLocked.countDown()
                    check(releaseUpload.await(10, TimeUnit.SECONDS))
                    FilesTable.insert {
                        it[id] = fileId
                        it[user] = userId
                        it[folder] = rootId
                        it[name] = "racing.bin"
                        it[size] = 32L
                        it[sha256] = "a".repeat(64)
                        it[storageKey] = "blobs/racing.bin"
                        it[deletedAt] = 0L
                    }
                }
            }
            assertTrue(folderLocked.await(10, TimeUnit.SECONDS), "upload transaction did not lock the bucket")

            val deletion = pool.submit<Result<List<String>>> {
                runBlocking { runCatching { deleteEmptyS3Bucket(userId, rootId) } }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var blocked = false
            while (System.nanoTime() < deadline && !blocked) {
                blocked = transaction(DatabaseFactory.db) {
                    exec(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS " +
                            "WHERE SESSION_STATE = 'BLOCKED' " +
                            "AND (EXECUTING_STATEMENT ILIKE '%folders%' " +
                            "OR EXECUTING_STATEMENT ILIKE '%upload_sessions%')",
                    ) { rs ->
                        rs.next()
                        rs.getInt(1) > 0
                    } ?: false
                }
                if (!blocked) Thread.sleep(10)
            }
            assertTrue(blocked, "bucket deletion did not reach a locked statement")

            releaseUpload.countDown()
            upload.get(10, TimeUnit.SECONDS)
            val result = deletion.get(10, TimeUnit.SECONDS)
            val error = result.exceptionOrNull()
            assertTrue(error is S3Exception, "expected BucketNotEmpty, got $result")
            assertEquals("BucketNotEmpty", error.code)
            val fileStillThere = transaction(DatabaseFactory.db) {
                FilesTable.selectAll().where { FilesTable.id eq fileId }.any()
            }
            assertTrue(fileStillThere, "deletion removed a file committed during its emptiness check")
            assertTrue(transaction(DatabaseFactory.db) { FoldersTable.selectAll().where { FoldersTable.id eq rootId }.any() })
        } finally {
            releaseUpload.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun managementCreateAndDeleteEmptyBucket() = testApplication {
        setup()
        val created = client.post("/api/s3/buckets") {
            header(HttpHeaders.Authorization, auth)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"name":"managed-bucket"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val id = buckets().buckets.single { it.name == "managed-bucket" }.folderId

        val deleted = client.delete("/api/s3/buckets/$id") { header(HttpHeaders.Authorization, auth) }

        assertEquals(HttpStatusCode.NoContent, deleted.status, deleted.bodyAsText())
        assertTrue(buckets().buckets.isEmpty())
    }

    @Test
    fun managementMutationsRequireAuthentication() = testApplication {
        setup()
        val created = client.post("/api/s3/buckets") {
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"name":"managed-bucket"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, created.status)
        val deleted = client.delete("/api/s3/buckets/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.Unauthorized, deleted.status)
    }

    @Test
    fun endpointRequiresAuthentication() = testApplication {
        setup()
        val resp = client.get("/api/s3/buckets")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun tokenListCarriesS3ReadyFlag() = testApplication {
        setup()
        val created = client.post("/api/webdav/tokens") {
            header(HttpHeaders.Authorization, auth)
            header(HttpHeaders.ContentType, "application/json")
            setBody("""{"label":"new","readOnly":false}""")
        }.bodyAsText()
        val tokenId = Regex(""""id":"([^"]+)"""").find(created)!!.groupValues[1]
        val listResp = client.get("/api/webdav/tokens") { header(HttpHeaders.Authorization, auth) }
        assertTrue(listResp.bodyAsText().contains(""""s3Ready":true"""))

        transaction(DatabaseFactory.db) {
            WebdavTokensTable.update({ WebdavTokensTable.id eq UUID.fromString(tokenId) }) {
                it[WebdavTokensTable.secretEnc] = null
            }
        }
        val after = client.get("/api/webdav/tokens") { header(HttpHeaders.Authorization, auth) }
        assertTrue(after.bodyAsText().contains(""""s3Ready":false"""))
    }
}