package com.linan.barezen_drive.webdav

import com.linan.barezen_drive.api.ApiException
import com.linan.barezen_drive.api.Throttle
import com.linan.barezen_drive.api.clientIp
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.util.AttributeKey

/**
 * Where the `auth-webdav` provider parks the credential it resolved.
 *
 * A principal alone is not enough downstream: the routes must also know whether
 * this particular mount is read-only, and the JWT principal has no way to carry
 * that. Keeping it on the call rather than in a global also means two concurrent
 * mounts cannot see each other's token.
 */
val webdavTokenKey = AttributeKey<WebdavTokenRecord>("BareZenWebdavToken")

/**
 * Failed WebDAV authentications per address per window.
 *
 * Its own budget, never the login one. A mounted drive re-sends credentials on
 * every request, so a client holding a stale password produces a retry loop as a
 * matter of routine; charging that to `login` would spend the owner's login
 * quota and eventually lock the account out of its own drive. RFC 7617's
 * appendix calls out this hazard for implicit retries.
 */
const val DAV_AUTH_MAX_PER_WINDOW = 20
const val DAV_AUTH_WINDOW_MS = 300_000L

/**
 * Request budget for the resource routes.
 *
 * Far more generous than the login budget, because browsing a mount is a burst
 * of PROPFINDs and GETs by design - a limit tuned for interactive sign-ins
 * would throttle normal use of a mounted drive.
 */
const val DAV_MAX_PER_WINDOW = 600
const val DAV_WINDOW_MS = 60_000L

/**
 * Methods that change state. A read-only mount is refused on exactly these.
 *
 * Kept as an explicit set rather than "anything that is not GET/HEAD/OPTIONS"
 * because the safer default is the other way round: a method added later must
 * not silently become writable on a read-only mount.
 */
private val DAV_WRITE_METHODS = setOf("PUT", "MKCOL", "MOVE", "COPY", "DELETE", "PROPPATCH", "LOCK", "UNLOCK")

fun ApplicationCall.isDavWrite(): Boolean = request.httpMethod.value.uppercase() in DAV_WRITE_METHODS

/**
 * The credential behind this call, or a 401.
 *
 * Routes live inside `authenticate("auth-webdav")`, so the principal is
 * guaranteed; the token is not, and the read-only flag lives on the token.
 */
fun ApplicationCall.requireDavToken(): WebdavTokenRecord =
    attributes.getOrNull(webdavTokenKey)
        ?: throw ApiException.unauthorized("WebDAV 凭据缺失")

/** 403 for a write attempted with a read-only mount. */
fun ApplicationCall.davWritable(): WebdavTokenRecord {
    val token = requireDavToken()
    if (token.readOnly && isDavWrite()) {
        throw ApiException.forbidden("此挂载点为只读")
    }
    return token
}

/**
 * Consume from the mount's own request budget, refusing with 429 when it is
 * spent.
 *
 * Returns Unit on purpose. An earlier version returned a Boolean, and two of the
 * three call sites discarded it - including PROPFIND, the single most frequent
 * method a mount issues, which left the whole budget unenforced exactly where it
 * mattered. A checker that can be ignored is not a limit; making the refusal
 * happen here means a caller cannot forget it.
 */
fun ApplicationCall.davRequireQuota(max: Int = DAV_MAX_PER_WINDOW, windowMs: Long = DAV_WINDOW_MS) {
    if (!Throttle.allow("dav:" + clientIp(), max, windowMs)) {
        throw ApiException.rateLimited("挂载请求过于频繁")
    }
}
