package com.linan.barezen_drive

import com.linan.barezen_drive.auth.AuthService
import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

/**
 * The password KDF is the one piece of work in the auth path that cannot be made
 * fast: bcrypt at cost 10 spends ~100ms of pure CPU per login (and one more for
 * the dummy hash when the username does not exist). Holding a pooled JDBC
 * connection across that window is what turns a login spike into an outage -
 * the pool is 5 connections wide (DatabaseFactory), so five simultaneous
 * logins park every connection and unrelated work (uploads, folder listings)
 * then waits on Hikari's 30s timeout.
 *
 * These tests pin the invariant rather than the timing: no Exposed transaction
 * may be open while the KDF runs, so no connection is held across it. The
 * verifier is injected so the test can observe the transaction state from
 * inside the call that must not hold one.
 */
class AuthTransactionScopeTest {

    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef",
        Files.createTempDirectory("bz-authscope").toString(), 1L shl 30,
    )

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        // The module block is lazy: without a request the app never starts and
        // there is no schema for AuthService to read.
        client.get("/health")
    }

    @Test
    fun loginVerifiesThePasswordOutsideAnyTransaction() = testApplication {
        setup()
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"scope1","password":"password123"}""")
        }

        var ambientDuringVerify: Any? = "never-ran"
        val resp = withContext(Dispatchers.IO) {
            AuthService.login("scope1", "password123") { _, _ ->
                ambientDuringVerify = TransactionManager.currentOrNull()
                true
            }
        }

        assertNull(
            ambientDuringVerify,
            "a pooled connection was held across the KDF: $ambientDuringVerify",
        )
        assertTrue(resp.accessToken.isNotBlank())
    }

    @Test
    fun loginOfUnknownUserStillBurnsAKdfOutsideAnyTransaction() = testApplication {
        setup()
        var ambientDuringVerify: Any? = "never-ran"
        var called = 0

        val error = assertFailsWith<Exception> {
            withContext(Dispatchers.IO) {
                AuthService.login("ghost", "password123") { _, _ ->
                    called++
                    ambientDuringVerify = TransactionManager.currentOrNull()
                    false
                }
            }
        }

        assertEquals(1, called, "the dummy verification must still happen (timing oracle)")
        assertNull(ambientDuringVerify, "the dummy verification must not hold a connection either")
        assertTrue(
            error.message?.contains("用户名或密码错误") == true,
            "unexpected error: ${error.message}",
        )
    }

    @Test
    fun registerHashesThePasswordOutsideAnyTransaction() = testApplication {
        setup()
        var ambientDuringHash: Any? = "never-ran"

        val user = withContext(Dispatchers.IO) {
            AuthService.register("scope2", "password123") {
                ambientDuringHash = TransactionManager.currentOrNull()
                "\$2a\$10\$abcdefghijklmnopqrstuv"
            }
        }

        assertNull(ambientDuringHash, "a pooled connection was held across the KDF: $ambientDuringHash")
        assertEquals("scope2", user.username)
    }

    @Test
    fun registerStillRejectsADuplicateUsername() = testApplication {
        setup()
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"dupe","password":"password123"}""")
        }
        val second = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"dupe","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.Conflict, second.status, second.bodyAsText())
    }
}
