package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-account budget stops password guessing at 20 failures per 15 minutes,
 * and it is the second axis behind the per-IP limiter (which an attacker rotates
 * around freely). It used to be enforced with peek() before the attempt and
 * record() after a failure - two separate steps, so a burst of parallel requests
 * all read the same count before any of them wrote it and every one of them was
 * let through.
 *
 * The test fires the burst for real: 40 concurrent wrong passwords, each from
 * its own address so the per-IP limiter stays out of the way, and asserts that
 * no more than the budget got a chance to guess. A correct password afterwards
 * must be refused too - the account is locked, not merely noisy.
 */
class LoginLockTest {
    private fun cfg() = AppConfig(
        port = 0,
        jdbcUrl = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        dbUser = "sa", dbPassword = "",
        jwtSecret = "test-secret-0123456789abcdef0123456789abcdef",
        storageDir = Files.createTempDirectory("bz-lock").toString(),
        maxFileSize = 1L shl 30,
        trustProxy = true,
        trustedProxyCidrs = listOf("127.0.0.0/8"),
        registrationOpen = true,
    )

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        client.post("/api/auth/register") {
            header("X-Forwarded-For", "10.0.0.1")
            contentType(ContentType.Application.Json)
            setBody("""{"username":"target","password":"password123"}""")
        }
    }

    private suspend fun ApplicationTestBuilder.guess(ip: String, password: String) =
        client.post("/api/auth/login") {
            header("X-Forwarded-For", ip)
            contentType(ContentType.Application.Json)
            setBody("""{"username":"target","password":"$password"}""")
        }

    @Test
    fun aBurstOfGuessesCannotPassTheAccountBudget() = testApplication {
        setup()
        val attempts = 40
        val statuses = coroutineScope {
            (1..attempts).map { i ->
                async { guess("198.51.100.$i", "wrongpass$i").status }
            }.awaitAll()
        }
        val notRateLimited = statuses.count { it != HttpStatusCode.TooManyRequests }
        assertTrue(
            notRateLimited <= 20,
            "at most 20 guesses per 15 min may reach the password check, but $notRateLimited of $attempts were let through: $statuses",
        )
        // The account is locked, so even the right password is refused.
        val correct = guess("203.0.113.200", "password123")
        assertEquals(HttpStatusCode.TooManyRequests, correct.status, correct.bodyAsText())
    }

    /**
     * The other half of the atomic reservation: an honest login must not spend
     * the budget, or a household sharing one account locks itself out.
     */
    @Test
    fun successfulLoginsDoNotSpendTheFailureBudget() = testApplication {
        setup()
        repeat(30) { i ->
            val ok = guess("198.51.100.$i", "password123")
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        }
    }
}
