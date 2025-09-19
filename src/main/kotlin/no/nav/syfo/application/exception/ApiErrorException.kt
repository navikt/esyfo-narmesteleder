package no.nav.syfo.application.exception

import io.ktor.http.*
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType

sealed class ApiErrorException(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    abstract fun toApiError(path: String): ApiError

    class ForbiddenException(
        val errorMessage: String = "Forbidden",
        cause: Throwable? = null,
    ) : ApiErrorException(errorMessage, cause) {
        override fun toApiError(path: String) = ApiError(
            path = path,
            status = HttpStatusCode.Forbidden,
            type = ErrorType.AUTHORIZATION_ERROR,
            message = errorMessage
        )
    }

    class InternalServerErrorException(
        val errorMessage: String = "Internal Server Error",
        cause: Throwable? = null,
    ) : ApiErrorException(errorMessage, cause) {
        override fun toApiError(path: String) = ApiError(
            path = path,
            status = HttpStatusCode.InternalServerError,
            type = ErrorType.INTERNAL_SERVER_ERROR,
            message = errorMessage
        )
    }

    class UnauthorizedException(
        val errorMessage: String = "Unauthorized",
        cause: Throwable? = null,
    ) : ApiErrorException(errorMessage, cause) {
        override fun toApiError(path: String): ApiError = ApiError(
            path = path,
            status = HttpStatusCode.Unauthorized,
            type = ErrorType.AUTHORIZATION_ERROR,
            message = errorMessage
        )
    }

    class BadRequestException(
        val errorMessage: String = "Bad Request",
        cause: Throwable? = null,
    ) : ApiErrorException(errorMessage, cause) {
        override fun toApiError(path: String): ApiError = ApiError(
            path = path,
            status = HttpStatusCode.BadRequest,
            type = ErrorType.BAD_REQUEST,
            message = errorMessage
        )
    }
}