package no.nav.syfo.narmestelederrelasjon.api

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OPPGI_NARMESTELEDER_RESOURCE

internal fun DenialReason.toForbiddenException(organizationNumber: OrganizationNumber): ApiErrorException.ForbiddenException = ApiErrorException.ForbiddenException(
    errorMessage = when (this) {
        DenialReason.MISSING_ORGANIZATION_ACCESS -> "User lacks access to organization: ${organizationNumber.value}"
        DenialReason.MISSING_RESOURCE_ACCESS ->
            "User lacks access to required Altinn resource for organization: ${organizationNumber.value}"
        DenialReason.SYSTEM_USER_REJECTED -> "System user does not have access to $OPPGI_NARMESTELEDER_RESOURCE resource"
    },
    type = if (this == DenialReason.MISSING_ORGANIZATION_ACCESS) ErrorType.MISSING_ORG_ACCESS else ErrorType.MISSING_ALITINN_RESOURCE_ACCESS,
    isAlreadyLogged = this == DenialReason.SYSTEM_USER_REJECTED,
)
