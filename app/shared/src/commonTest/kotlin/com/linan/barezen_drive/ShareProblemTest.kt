package com.linan.barezen_drive

import com.linan.barezen_drive.data.api.ApiFailure
import com.linan.barezen_drive.ui.screens.share.ShareProblem
import com.linan.barezen_drive.ui.screens.share.shareProblemFor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A shared link that could not be opened used to be declared dead on any
 * failure whatsoever: the visitor was told to go and ask the sender for a new
 * link when what actually happened was that their phone had no signal, and one
 * tap on retry would have loaded it. Only a status that genuinely means "this
 * token will never work" may say so.
 */
class ShareProblemTest {

    @Test
    fun anUnknownTokenIsADeadLink() {
        // The server answers revoked/expired/never-existed identically.
        assertEquals(ShareProblem.INVALID, shareProblemFor(ApiFailure.Http("NOT_FOUND", "not found", 404)))
    }

    @Test
    fun aGoneTokenIsADeadLink() {
        assertEquals(ShareProblem.INVALID, shareProblemFor(ApiFailure.Http(null, "gone", 410)))
    }

    @Test
    fun aNetworkFailureIsNotADeadLink() {
        // This is the case the old code got wrong: offline, DNS failure, a
        // timeout, a dropped connection mid-response.
        assertEquals(ShareProblem.UNREACHABLE, shareProblemFor(ApiFailure.Network("timeout")))
    }

    @Test
    fun aServerErrorIsNotADeadLink() {
        // 5xx is the server's problem, not the link's; retrying is the answer.
        assertEquals(ShareProblem.UNREACHABLE, shareProblemFor(ApiFailure.Http(null, "boom", 500)))
        assertEquals(ShareProblem.UNREACHABLE, shareProblemFor(ApiFailure.Http(null, "bad gateway", 502)))
    }

    @Test
    fun aRateLimitOrUnrelatedClientErrorIsNotADeadLink() {
        assertEquals(ShareProblem.UNREACHABLE, shareProblemFor(ApiFailure.Http("RATE_LIMIT", "slow down", 429)))
        assertEquals(ShareProblem.UNREACHABLE, shareProblemFor(ApiFailure.Http(null, "teapot", 418)))
    }

    @Test
    fun anUnauthorizedIsNotADeadLink() {
        // The share endpoints are public, so a 401 here is a misconfigured or
        // proxied server rather than a verdict on the token.
        assertEquals(ShareProblem.UNREACHABLE, shareProblemFor(ApiFailure.Unauthorized()))
    }

    @Test
    fun anUnrecognisedThrowableIsTreatedAsUnreachable() {
        // Defensive: a decode error or cancellation must not tell a visitor
        // their link is dead.
        assertEquals(ShareProblem.UNREACHABLE, shareProblemFor(IllegalStateException("decode")))
    }
}
