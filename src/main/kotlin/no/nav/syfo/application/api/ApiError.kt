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
    BAD_REQUEST_INVALID_FORMAT,
    CONFLICT,
    BAD_REQUEST_EMPLOYMENT_MISSING_IN_ORG_LINEMANAGER,
    BAD_REQUEST_EMPLOYMENT_MISSING_IN_ORG_EMPLOYEE,
    BAD_REQUEST_NAME_NIN_MISMATCH_LINEMANAGER,
    BAD_REQUEST_NAME_NIN_MISMATCH_EMPLOYEE,
    FORBIDDEN_MISSING_ORG_ACCESS,
    FORBIDDEN_MISSING_ALITINN_RESOURCE_ACCESS,
    BAD_REQUEST_NO_ACTIVE_SICK_LEAVE
}
