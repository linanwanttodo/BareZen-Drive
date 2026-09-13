package com.linan.barezen_drive.api

import java.util.UUID

/**
 * Parses a UUID from unvalidated request input (path parameter or body field).
 * Malformed input becomes a 400 VALIDATION_ERROR instead of an unhandled
 * IllegalArgumentException that would surface as 500.
 */
fun String.toUuidOrBadRequest(): UUID =
    runCatching { UUID.fromString(this) }
        .getOrElse { throw ApiException.badRequest("ID 格式非法") }
