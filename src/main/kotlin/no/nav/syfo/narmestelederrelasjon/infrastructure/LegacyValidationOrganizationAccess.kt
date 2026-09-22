package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonOrganizationAccess

class LegacyValidationOrganizationAccess(
    private val validationService: ValidationService,
) : NarmestelederrelasjonOrganizationAccess {

    override suspend fun hasAccess(
        principal: Principal,
        orgNumber: String,
    ): Boolean = try {
        validationService.validatePrincipalAccessToOrgnumber(
            principal = principal,
            orgNumber = OrganizationNumber(orgNumber),
        )
        true
    } catch (_: ApiErrorException.ForbiddenException) {
        false
    }
}
