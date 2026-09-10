package com.linan.barezen_drive.api

import com.linan.barezen_drive.core.dto.ApiError
import com.linan.barezen_drive.core.dto.ErrorCodes
import io.ktor.http.*

class ApiException(val code: String, val status: HttpStatusCode, override val message: String) : RuntimeException(message) {
    val error get() = ApiError(code, message)
    companion object {
        fun badRequest(message: String, code: String = ErrorCodes.VALIDATION_ERROR) =
            ApiException(code, HttpStatusCode.BadRequest, message)
        fun notFound(message: String = "资源不存在") =
            ApiException(ErrorCodes.NOT_FOUND, HttpStatusCode.NotFound, message)
        fun conflict(code: String, message: String) =
            ApiException(code, HttpStatusCode.Conflict, message)
        fun unauthorized(message: String, code: String = ErrorCodes.INVALID_CREDENTIALS) =
            ApiException(code, HttpStatusCode.Unauthorized, message)
        fun forbidden(message: String, code: String = ErrorCodes.VALIDATION_ERROR) =
            ApiException(code, HttpStatusCode.Forbidden, message)
    }
}
