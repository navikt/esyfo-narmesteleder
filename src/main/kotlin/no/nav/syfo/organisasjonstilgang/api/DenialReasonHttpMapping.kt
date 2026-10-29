package no.nav.syfo.organisasjonstilgang.api

import io.ktor.http.HttpStatusCode
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.platform.api.ApiError
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.api.ErrorType

internal fun DenialReason.toForbiddenException(
    organizationNumber: OrganizationNumber,
    isAlreadyLogged: Boolean = this == DenialReason.SYSTEM_USER_REJECTED,
): ApiErrorException.ForbiddenException {
    val error = toForbiddenApiError(organizationNumber)
    return ApiErrorException.ForbiddenException(
        errorMessage = error.message,
        type = error.type,
        isAlreadyLogged = isAlreadyLogged,
    )
}

internal fun DenialReason.toForbiddenApiError(
    organizationNumber: OrganizationNumber,
    path: String? = null,
): ApiError = ApiError(
    status = HttpStatusCode.Forbidden,
    message = when (this) {
        DenialReason.MISSING_ORGANIZATION_ACCESS -> "User lacks access to organization: ${organizationNumber.value}"
        DenialReason.MISSING_RESOURCE_ACCESS ->
            "User lacks access to required Altinn resource for organization: ${organizationNumber.value}"
        DenialReason.SYSTEM_USER_REJECTED -> "System user does not have access to $OPPGI_NARMESTELEDER_RESOURCE resource"
    },
    type = if (this == DenialReason.MISSING_ORGANIZATION_ACCESS) ErrorType.MISSING_ORG_ACCESS else ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
    path = path,
)
