package no.nav.syfo.application.api

import io.ktor.http.HttpStatusCode
import java.time.Instant

open class ApiError(
    val status: HttpStatusCode,
    val type: ErrorType,
    open val message: String,
    open val path: String? = null,
    val timestamp: Instant = Instant.now(),
)

enum class ErrorType {
    AUTHENTICATION_ERROR,
    AUTHORIZATION_ERROR,
    NOT_FOUND,
    INTERNAL_SERVER_ERROR,
    ILLEGAL_ARGUMENT,
    BAD_REQUEST,
    CONFLICT,
    BAD_REQUEST_NAME_NIN_MISMATCH,
    FORBIDDEN_SYSTEM_LACKS_ORG_ACCESS,
    FORBIDDEN_SYSTEM_LACKS_ALITINN_RESOURCE_ACCESS,
    BAD_REQUEST_NO_ACTIVE_SICK_LEAVE
}
