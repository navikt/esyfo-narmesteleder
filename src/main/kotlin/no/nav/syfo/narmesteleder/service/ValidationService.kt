package no.nav.syfo.narmesteleder.service

import no.nav.syfo.application.auth.Principal
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator

class ValidationService(
    private val principalAccessValidator: PrincipalAccessValidator,
) {
    suspend fun validatePrincipalAccessToOrgnumber(
        principal: Principal,
        orgNumber: OrganizationNumber,
    ): String? = principalAccessValidator.validatePrincipalAccessToOrgnumber(principal, orgNumber.value)
}
