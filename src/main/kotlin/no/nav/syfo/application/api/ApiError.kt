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
    INVALID_FORMAT,
    CONFLICT,
    LINEMANAGER_MISSING_EMPLOYMENT_IN_ORG,
    EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG,
    LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
    EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
    MISSING_ORG_ACCESS,
    MISSING_ALITINN_RESOURCE_ACCESS,
    NO_ACTIVE_SICK_LEAVE,
    UPSTREAM_SERVICE_UNAVAILABLE,
    MISSING_REQUIRED_PARAMETER,
    ORGANIZATION_NOT_FOUND,
}
