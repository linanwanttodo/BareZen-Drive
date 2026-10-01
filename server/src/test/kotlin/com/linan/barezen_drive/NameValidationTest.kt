package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

/**
 * A file named "." or ".." is a legal string to the API and a hazard to a
 * filesystem-shaped protocol: WebDAV resolves "/a/../b" as "/b", so a real
 * folder called ".." makes the client's intent ambiguous, and any
 * implementation that walks the path textually can be walked out of the user's
 * root. Control characters are worse: XML 1.0 forbids them raw, so one such
 * name turns a 207 Multi-Status listing into a document no client can parse -
 * the whole directory disappears, not just the one file.
 *
 * Both are rejected at the shared validation layer, so the fix is not WebDAV's.
 */
class NameValidationTest {
    private val storageDir = Files.createTempDirectory("bz-names").toString()
    private val json = Json { ignoreUnknownKeys = true }
    private var auth = ""

    private suspend fun ApplicationTestBuilder.setup() {
        val c = AppConfig(
            0, "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
            registrationOpen = true,
        )
        application { module(c, LocalStorageProvider(Path.of(c.storageDir))) }
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""")
        }
        auth = "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(
            client.post("/api/auth/login") {
                contentType(ContentType.Application.Json); setBody("""{"username":"user1","password":"password123"}""")
            }.bodyAsText()
        )!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.createFolder(name: String) =
        client.post("/api/folders") {
            header(HttpHeaders.Authorization, auth); contentType(ContentType.Application.Json); setBody("""{"name":${json.encodeToString(name)}}""")
        }

    @Test
    fun dotDotIsNotAFolderName() = testApplication {
        setup()
        val dotDot = createFolder("..")
        assertEquals(HttpStatusCode.BadRequest, dotDot.status, "a name of \"..\" must be refused: ${dotDot.bodyAsText()}")
        val dot = createFolder(".")
        assertEquals(HttpStatusCode.BadRequest, dot.status, "a name of \".\" must be refused: ${dot.bodyAsText()}")
    }

    @Test
    fun controlCharactersAreNotAName() = testApplication {
        setup()
        // Escape sequences, not raw bytes: a literal NUL in this source file is
        // what makes a text file read as binary, and the assertion message then
        // cannot be read either.
        for (bad in listOf("a\u0000b", "a\u001Fb", "a\u007Fb", "a\u0009b", "a\u000Ab")) {
            val r = createFolder(bad)
            assertEquals(
                HttpStatusCode.BadRequest, r.status,
                "control characters must be rejected, got through: ${bad.map { it.code }} -> ${r.status} ${r.bodyAsText()}",
            )
        }
    }

    @Test
    fun aTabOrNewlineIsRejectedButOrdinaryUnicodeIsNot() = testApplication {
        setup()
        assertEquals(HttpStatusCode.BadRequest, createFolder("a\tb").status)
        assertEquals(HttpStatusCode.BadRequest, createFolder("a\nb").status)
        // Non-ASCII and the other characters a filesystem allows must still work.
        for (good in listOf("照片 2026", "a b", "a#b", "a?b", "a%b", "..文档", ".hidden")) {
            val r = createFolder(good)
            assertEquals(HttpStatusCode.Created, r.status, "must stay legal: $good -> ${r.status} ${r.bodyAsText()}")
        }
    }
}
