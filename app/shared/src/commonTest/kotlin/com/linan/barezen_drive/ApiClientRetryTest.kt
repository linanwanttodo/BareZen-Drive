package com.linan.barezen_drive

import com.linan.barezen_drive.data.api.ApiClient
import com.linan.barezen_drive.data.repo.FilesRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The connect-level retry exists for the failures where the request never reached
 * the server, so replaying it cannot duplicate anything. Deciding that from the
 * *text* of the exception meant any failure that happened to carry those words -
 * a server-returned error body, a mid-exchange failure - was replayed too, and on
 * a POST that is a duplicate folder or a duplicate share from one user action.
 *
 * What is left is the one signal that actually proves nothing was sent.
 */
class ApiClientRetryTest {

    private fun engineThatFails(times: MutableList<Int>, failure: () -> Throwable): MockEngine =
        MockEngine {
            times.add(1)
            throw failure()
        }

    @Test
    fun aFailureWhoseTextOnlyLooksLikeADeadConnectionIsNotReplayed() = runTest {
        val attempts = mutableListOf<Int>()
        val store = FakeTokenStore()
        val engine = engineThatFails(attempts) {
            IllegalStateException("Failed to connect to 127.0.0.1:8080")
        }
        val res = FilesRepository(ApiClient(store, engine)).createFolder(null, "docs")
        assertTrue(res.isFailure)
        assertEquals(1, attempts.size, "a create that may have landed must not be sent again: $attempts")
    }

    @Test
    fun aServerErrorIsNotReplayedEither() = runTest {
        val attempts = mutableListOf<Int>()
        val store = FakeTokenStore()
        val engine = MockEngine {
            attempts.add(1)
            respond(
                """{"error":{"code":"BOOM","message":"Failed to connect to the storage backend"}}""",
                HttpStatusCode.InternalServerError,
                headersOf("Content-Type", "application/json"),
            )
        }
        val res = FilesRepository(ApiClient(store, engine)).createFolder(null, "docs")
        assertTrue(res.isFailure)
        assertEquals(1, attempts.size, "the server answered; replaying would run the create twice")
    }

    @Test
    fun aConnectionThatNeverOpenedIsRetried() = runTest {
        val attempts = mutableListOf<Int>()
        val store = FakeTokenStore()
        val engine = engineThatFails(attempts) {
            io.ktor.client.network.sockets.ConnectTimeoutException("http://test", IllegalStateException("socket closed"))
        }
        val res = FilesRepository(ApiClient(store, engine)).createFolder(null, "docs")
        assertTrue(res.isFailure)
        assertEquals(3, attempts.size, "the only failure that proves nothing was sent is worth another try")
    }
}
