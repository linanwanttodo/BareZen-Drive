package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.*

class AdminUsersTest {
    private val storageDir = Files.createTempDirectory("bz-admin").toString()

    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
        updateRepoUrl = "https://example.invalid/not-github",
    )

    @Test
    fun listAndDeleteUsers() = testApplication {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        suspend fun token(name: String): String {
            client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"$name","password":"password123"}""")
            }
            val login = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"$name","password":"password123"}""")
            }
            return Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
        }
        val owner = token("owner")
        val second = token("second")
        val ownerH = "Bearer $owner"

        // Anonymous list is rejected.
        val anon = client.get("/api/admin/users")
        assertEquals(HttpStatusCode.Unauthorized, anon.status)

        val list = client.get("/api/admin/users") { header(HttpHeaders.Authorization, ownerH) }
        assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
        val body = list.bodyAsText()
        assertTrue(body.contains(""""username":"owner"""") && body.contains(""""username":"second"""") , body)

        // Owner deletes the second account; its session dies with it.
        val users = Regex(""""id":"([^"]+)","username":"second"""").find(body)!!.groupValues[1]
        val del = client.delete("/api/admin/users/$users") { header(HttpHeaders.Authorization, ownerH) }
        assertEquals(HttpStatusCode.NoContent, del.status, del.bodyAsText())

        val after = client.get("/api/admin/users") { header(HttpHeaders.Authorization, ownerH) }
        assertFalse(after.bodyAsText().contains(""""username":"second"""") , after.bodyAsText())

        val login2 = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"second","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, login2.status, login2.bodyAsText())

        // The second token was revoked together with the account.
        val withDead = client.get("/api/me") { header(HttpHeaders.Authorization, "Bearer $second") }
        assertEquals(HttpStatusCode.Unauthorized, withDead.status)
    }
}
