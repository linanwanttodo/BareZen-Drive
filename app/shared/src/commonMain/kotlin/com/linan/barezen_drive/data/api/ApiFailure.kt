package com.linan.barezen_drive.data.api

/**
 * Failure type carried by kotlin.Result from every repository/API call.
 * code is the server error envelope code (e.g. NAME_CONFLICT) when the server
 * answered with an HTTP error body, null otherwise. httpStatus is 0 for
 * network/serialization failures and the real status for HTTP errors.
 * [Unauthorized] is used when the refresh flow itself failed with 401/400.
 */
sealed class ApiFailure(message: String) : Exception(message) {
    abstract val code: String?
    abstract val httpStatus: Int

    class Network(message: String) : ApiFailure(message) {
        override val code: String? = null
        override val httpStatus: Int = 0
    }

    class Http(
        override val code: String?,
        message: String,
        override val httpStatus: Int,
    ) : ApiFailure(message)

    class Unauthorized(message: String = "Session expired") : ApiFailure(message) {
        override val code: String? = null
        override val httpStatus: Int = 401
    }
}
