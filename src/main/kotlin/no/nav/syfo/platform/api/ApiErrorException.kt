package no.nav.syfo.platform.api

import io.ktor.http.HttpStatusCode
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.platform.upstream.UpstreamFailure

sealed class ApiErrorException(
    message: String,
    val type: ErrorType,
    cause: Throwable?,
    val isAlreadyLogged: Boolean = false,
    val upstreamFailure: UpstreamFailure? = null,
) : RuntimeException(message, cause) {
    abstract fun toApiError(path: String): ApiError

    class ForbiddenException(
        val errorMessage: String = "Forbidden",
        cause: Throwable? = null,
        type: ErrorType = ErrorType.AUTHENTICATION_ERROR,
        isAlreadyLogged: Boolean = false,
    ) : ApiErrorException(errorMessage, type, cause, isAlreadyLogged) {
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
        isAlreadyLogged: Boolean = false,
        upstreamFailure: UpstreamFailure? = null,
    ) : ApiErrorException(errorMessage, type, cause, isAlreadyLogged, upstreamFailure) {
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
        isAlreadyLogged: Boolean = false,
    ) : ApiErrorException(errorMessage, type, cause, isAlreadyLogged) {
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
        isAlreadyLogged: Boolean = false,
        private val includePath: Boolean = true,
    ) : ApiErrorException(errorMessage, type, cause, isAlreadyLogged) {
        override fun toApiError(path: String): ApiError = ApiError(
            path = path.takeIf { includePath },
            status = HttpStatusCode.NotFound,
            type = type,
            message = errorMessage
        )
    }
}

fun UpstreamFailure.toUpstreamUnavailableException(): ApiErrorException.InternalServerErrorException = ApiErrorException.InternalServerErrorException(
    errorMessage = "An upstream service is unavailable",
    type = ErrorType.UPSTREAM_SERVICE_UNAVAILABLE,
    cause = cause,
    upstreamFailure = this,
)

internal fun DenialReason.toForbiddenException(organizationNumber: OrganizationNumber): ApiErrorException.ForbiddenException = ApiErrorException.ForbiddenException(
    errorMessage = when (this) {
        DenialReason.MISSING_ORGANIZATION_ACCESS -> "User lacks access to organization: ${organizationNumber.value}"
        DenialReason.MISSING_RESOURCE_ACCESS ->
            "User lacks access to required Altinn resource for organization: ${organizationNumber.value}"
        DenialReason.SYSTEM_USER_REJECTED -> "System user does not have access to ${OPPGI_NARMESTELEDER_RESOURCE} resource"
    },
    type = if (this == DenialReason.MISSING_ORGANIZATION_ACCESS) ErrorType.MISSING_ORG_ACCESS else ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
    isAlreadyLogged = this == DenialReason.SYSTEM_USER_REJECTED,
)
