package com.linan.barezen_drive.webdav

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.options
import io.ktor.server.routing.route

/**
 * The WebDAV surface, mounted at /dav.
 *
 * Outside /api on purpose: a mount speaks HTTP Basic, not the app's Bearer JWT,
 * and the two must not be interchangeable. Registered after the other real
 * routes so nothing shadows it, and before staticWeb() so an unmatched /dav path
 * is answered here rather than by the SPA fallback (see
 * StaticWeb.RESERVED_PREFIXES).
 *
 * Authenticated as a whole rather than per method, so an unauthenticated request
 * to any method gets a 401 with a `WWW-Authenticate` challenge instead of a bare
 * 404 that leaves the client with nothing to authenticate against.
 */
fun Route.webdavResourceRoutes() {
    authenticate("auth-webdav") {
        // Both spellings are registered, and the trailing-slash one is the point:
        // the canonical URL of a WebDAV collection ends in "/", and that is what
        // Finder, Explorer, davfs2 and rclone all send. Ktor's constant path
        // selector treats "/dav" and "/dav/" as different routes, so registering
        // only one of them means a client gets a 404 for the very URL it was
        // just handed and reports the mount as broken.
        davSurface()
        route("/dav/") { davSurface() }
    }
}

private fun Route.davSurface() {
    // OPTIONS is the hard floor for a Windows mount to succeed at all, so it is
    // the one method registered up front. The rest arrive with the later tasks
    // and take precedence over the catch-all below.
    options {
        call.davAllowRequest()
        call.respond(HttpStatusCode.OK)
        call.response.header(HttpHeaders.Allow, DAV_ALLOW)
        call.response.header("DAV", "1")
        // Office reads this; harmless elsewhere.
        call.response.header("MS-Author-Via", "DAV")
    }

    // A catch-all, and not a cosmetic one. Ktor only runs the `authenticate`
    // challenge for a route that actually matched, so without a handler covering
    // the remaining methods an unauthenticated GET /dav/ would come back 404 and
    // the client would never be offered a challenge to answer.
    handle {
        call.respond(HttpStatusCode.NotFound)
    }
}

/**
 * Advertised capabilities.
 *
 * LOCK/UNLOCK are deliberately absent, and so is the `2` in `DAV:`. Neither is
 * implemented - they answer 501 - and advertising them changes client behaviour
 * for the worse: a client that believes locking is available declines to open a
 * file it cannot lock, and Office refuses to save one. Claiming a capability we
 * lack is worse than the 501 itself.
 *
 * `X-MSDAVEXT` is likewise withheld. It advertises the Windows-only name-mangling
 * extensions, and a client that trusts it starts sending `Translate: f` expecting
 * a rewritten display name.
 */
private const val DAV_ALLOW =
    "OPTIONS, HEAD, GET, PROPFIND, PUT, MKCOL, DELETE, MOVE, COPY"
