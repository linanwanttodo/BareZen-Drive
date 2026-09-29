package com.linan.barezen_drive

import com.linan.barezen_drive.config.AppConfig
import com.linan.barezen_drive.storage.LocalStorageProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who may create an account on this instance.
 *
 * Registration used to be open until the owner thought to close it, and the
 * owner was defined as "the first account that exists". On a fresh instance
 * those two facts combine into a takeover: whoever reaches the published port
 * first registers, becomes the owner, and can then list every user, delete any
 * account and reopen registration. Nothing in that path required an invite.
 *
 * The default is therefore closed, and an instance with no accounts yet accepts
 * exactly one account - from the machine itself (the install wizard seeds
 * BOOTSTRAP_ADMIN_*, and a local curl is the manual equivalent). The owner then
 * opens registration from the settings screen if the drive is meant to have
 * guests.
 */
class RegistrationSettingTest {
    private val storageDir = Files.createTempDirectory("bz-reg").toString()

    private fun cfg() = AppConfig(
        0,
        "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
        updateRepoUrl = "https://example.invalid/not-github",
    )

    private var auth = ""

    private suspend fun ApplicationTestBuilder.setup() {
        val c = cfg()
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
    }

    private suspend fun ApplicationTestBuilder.token(user: String): String {
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$user","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
        return "Bearer " + Regex(""""accessToken":"([^"]+)"""").find(login.bodyAsText())!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.register(user: String, forwardedFor: String? = null) =
        client.post("/api/auth/register") {
            if (forwardedFor != null) header("X-Forwarded-For", forwardedFor)
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$user","password":"password123"}""")
        }

    /**
     * The bootstrap slot is only for the host: a request that arrived through a
     * proxy (it carries a forwarding header) is not the operator, even if the
     * proxy happens to sit on the same machine.
     */
    @Test
    fun aFreshInstanceOnlyTakesItsFirstAccountFromTheHost() = testApplication {
        setup()
        val proxied = register("intruder", forwardedFor = "203.0.113.9")
        assertEquals(HttpStatusCode.Forbidden, proxied.status, proxied.bodyAsText())
        assertTrue(proxied.bodyAsText().contains("REGISTRATION_DISABLED"), proxied.bodyAsText())

        // The same request straight from the host is the install-time account.
        val owner = register("owner1")
        assertEquals(HttpStatusCode.Created, owner.status, owner.bodyAsText())
    }

    @Test
    fun registrationDefaultsToClosedAndTheOwnerOpensIt() = testApplication {
        setup()
        assertEquals(HttpStatusCode.Created, register("owner1").status)

        // Default: closed. A visitor who guessed the URL is refused.
        val before = client.get("/api/settings/registration")
        assertEquals(HttpStatusCode.OK, before.status, before.bodyAsText())
        assertTrue(before.bodyAsText().contains("\"open\":false"), before.bodyAsText())
        val refused = register("intruder")
        assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
        assertTrue(refused.bodyAsText().contains("REGISTRATION_DISABLED"), refused.bodyAsText())

        // The owner exists and is not locked out by the new default.
        auth = token("owner1")

        // Unauthenticated toggle is rejected.
        val anon = client.patch("/api/settings/registration") {
            contentType(ContentType.Application.Json); setBody("""{"open":false}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, anon.status)

        // The owner opens registration; further sign-ups are accepted.
        val open = client.patch("/api/settings/registration") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json); setBody("""{"open":true}""")
        }
        assertEquals(HttpStatusCode.OK, open.status, open.bodyAsText())
        assertTrue(open.bodyAsText().contains("\"open\":true"), open.bodyAsText())
        val guest = register("second")
        assertEquals(HttpStatusCode.Created, guest.status, guest.bodyAsText())

        // The newly admitted account is a guest, not an administrator: it can
        // sign in but must not be able to flip the registration switch.
        val guestAuth = token("second")
        val guestToggle = client.patch("/api/settings/registration") {
            header(HttpHeaders.Authorization, guestAuth)
            contentType(ContentType.Application.Json); setBody("""{"open":false}""")
        }
        assertEquals(HttpStatusCode.Forbidden, guestToggle.status, guestToggle.bodyAsText())
        // The switch still reads open - the guest's attempt changed nothing.
        val stillOpen = client.get("/api/settings/registration")
        assertTrue(stillOpen.bodyAsText().contains("\"open\":true"), stillOpen.bodyAsText())

        // Closing it again shuts the door, including for the host.
        val close = client.patch("/api/settings/registration") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json); setBody("""{"open":false}""")
        }
        assertEquals(HttpStatusCode.OK, close.status, close.bodyAsText())
        val refusedAgain = register("third")
        assertEquals(HttpStatusCode.Forbidden, refusedAgain.status, refusedAgain.bodyAsText())
    }

    /**
     * An install that opted into an open registration window at the config
     * level (REGISTRATION_OPEN=true, or a test that needs several accounts)
     * behaves exactly as before: the first account is not special and nobody has
     * to open the door first.
     */
    @Test
    fun anExplicitlyOpenInstanceSkipsTheBootstrapStep() = testApplication {
        val c = AppConfig(
            0,
            "jdbc:h2:mem:${java.util.UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
            "sa", "", "test-secret-0123456789abcdef0123456789abcdef", storageDir, 1L shl 30,
            registrationOpen = true,
        )
        application { module(c, LocalStorageProvider(java.nio.file.Path.of(c.storageDir))) }
        val status = client.get("/api/settings/registration")
        assertTrue(status.bodyAsText().contains("\"open\":true"), status.bodyAsText())
        assertEquals(HttpStatusCode.Created, register("first").status)
        assertEquals(HttpStatusCode.Created, register("second").status)
    }
}
