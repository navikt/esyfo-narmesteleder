package no.nav.syfo.application.exception

import io.ktor.http.HttpStatusCode
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType

sealed class ApiErrorException(
    message: String,
    val type: ErrorType,
    cause: Throwable?
) : RuntimeException(message, cause) {
    abstract fun toApiError(path: String): ApiError

    class ForbiddenException(
        val errorMessage: String = "Forbidden",
        cause: Throwable? = null,
        type: ErrorType = ErrorType.AUTHENTICATION_ERROR,
    ) : ApiErrorException(errorMessage, type, cause) {
        override fun toApiError(path: String) = ApiError(
            path = path,
            status = HttpStatusCode.Forbidden,
            type = type,
            message = errorMessage
        )
    }

    class InternalServerErrorException(
        val errorMessage: String = "Internal Server Error",
        cause: Throwable? = null,
        type: ErrorType = ErrorType.INTERNAL_SERVER_ERROR,
    ) : ApiErrorException(errorMessage, type, cause) {
        override fun toApiError(path: String) = ApiError(
            path = path,
            status = HttpStatusCode.InternalServerError,
            type = type,
            message = errorMessage
        )
    }

    class UnauthorizedException(
        val errorMessage: String = "Unauthorized",
        cause: Throwable? = null,
        type: ErrorType = ErrorType.AUTHORIZATION_ERROR,
    ) : ApiErrorException(errorMessage, type, cause) {
        override fun toApiError(path: String): ApiError = ApiError(
            path = path,
            status = HttpStatusCode.Unauthorized,
            type = type,
            message = errorMessage
        )
    }

    class BadRequestException(
        val errorMessage: String = "Bad Request",
        cause: Throwable? = null,
        type: ErrorType = ErrorType.BAD_REQUEST,
    ) : ApiErrorException(errorMessage, type, cause) {
        override fun toApiError(path: String): ApiError = ApiError(
            path = path,
            status = HttpStatusCode.BadRequest,
            type = type,
            message = errorMessage
        )
    }

    class NotFoundException(
        val errorMessage: String = "Not Found",
        cause: Throwable? = null,
        type: ErrorType = ErrorType.NOT_FOUND,
    ) : ApiErrorException(errorMessage, type, cause) {
        override fun toApiError(path: String): ApiError = ApiError(
            path = path,
            status = HttpStatusCode.NotFound,
            type = type,
            message = errorMessage
        )
    }
}
