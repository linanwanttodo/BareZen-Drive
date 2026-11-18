package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.S3BucketsResponse
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.db.FilesTable
import com.linan.barezen_drive.db.FoldersTable
import com.linan.barezen_drive.db.WebdavTokensTable
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
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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