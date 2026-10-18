package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.core.dto.UploadCompleteResponse
import com.linan.barezen_drive.core.dto.UploadInitResponse
import com.linan.barezen_drive.db.DatabaseFactory
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A name search is `lower(name) LIKE '%q%'`, and a leading wildcard is the one
 * predicate a btree cannot serve: every keystroke read the account's whole `files`
 * set and folded each name, on a one-core box where that scan also pushes out the
 * pages everything else wanted. The answer is a GIN trigram index over exactly the
 * expression the query uses - which only works if the DDL and the query agree on
 * that expression, so both halves are pinned here.
 *
 * The index itself is PostgreSQL-only (H2 has no `gin_trgm_ops`, and the DDL is
 * skipped on a non-PostgreSQL product), so what a test can hold is the statement
 * text and the fact that a boot without it still searches correctly: a refused
 * `CREATE EXTENSION` must degrade to a slower search, never to a broken one.
 */
class SearchIndexDdlTest {
    private val storageDir = Files.createTempDirectory("bz-search").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""

    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
        registrationOpen = true,
    )

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.get("/health")
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"searcher","password":"password123"}""")
        }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"searcher","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.upload(name: String) {
        val body = name.encodeToByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val init = client.post("/api/uploads/init") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name","size":${body.size},"sha256":"$sha","mimeType":"text/plain"}""")
        }
        val ir = json.decodeFromString<UploadInitResponse>(init.bodyAsText())
        client.put("/api/uploads/${ir.uploadId}/chunks/0") {
            header(HttpHeaders.Authorization, auth)
            setBody(body)
        }
        val done = client.post("/api/uploads/${ir.uploadId}/complete") { header(HttpHeaders.Authorization, auth) }
        json.decodeFromString<UploadCompleteResponse>(done.bodyAsText())
    }

    @Test
    fun theIndexCoversTheSearchedExpression() {
        val ddl = DatabaseFactory.nameTrigramDdl()
        assertEquals(2, ddl.size, "enable the extension, then build on it")
        assertTrue(ddl[0].contains("pg_trgm"), ddl.toString())
        val index = ddl[1]
        // Booting must be repeatable: the DDL runs on every start, and a second
        // instance starting against the same database cannot fail on "index exists".
        assertTrue(index.contains("IF NOT EXISTS"), index)
        assertTrue(index.contains("gin_trgm_ops"), index)
        assertTrue(index.contains("lower(name)"), "the query folds the name with lower(); an " +
            "index on the bare column would not be usable by it: $index")
    }

    @Test
    fun searchStillMatchesASubstringWithoutTheIndex() = testApplication {
        setup()
        // H2 has no trigram operator class, so this boot skips the DDL entirely - the
        // search has to work anyway, just slower. That is the degradation the guard
        // promises, and it is worth pinning because the same guard silently disables
        // the index on every test run.
        upload("annual-report.txt")
        upload("holiday.png")
        upload("notes.txt")

        val res = client.get("/api/search?q=port") { header(HttpHeaders.Authorization, auth) }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val names = Regex(""""name":"([^"]+)"""").findAll(res.bodyAsText()).map { it.groupValues[1] }.toList()
        assertEquals(listOf("annual-report.txt"), names, "a substring hit, case-insensitively")
    }
}
