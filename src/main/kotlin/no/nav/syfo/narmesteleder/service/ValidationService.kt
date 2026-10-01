package no.nav.syfo.narmesteleder.service

import no.nav.syfo.application.auth.Principal
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.service.validators.NameValidator
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person

class ValidationService(
    private val pdlService: PdlService,
    private val principalAccessValidator: PrincipalAccessValidator,
) {
    suspend fun validateLinemanagerRevoke(
        linemanagerRevoke: LinemanagerRevoke,
        principal: Principal,
    ): Person {
        principalAccessValidator.validatePrincipalAccessToOrgnumber(
            principal,
            linemanagerRevoke.orgNumber.value,
        )
        val sykmeldt = pdlService.getPersonOrThrowApiError(linemanagerRevoke.employeeIdentificationNumber.value)
        NameValidator.validateEmployeeLastName(sykmeldt, linemanagerRevoke)

        return sykmeldt
    }

    suspend fun validatePrincipalAccessToOrgnumber(
        principal: Principal,
        orgNumber: OrganizationNumber,
    ): String? = principalAccessValidator.validatePrincipalAccessToOrgnumber(principal, orgNumber.value)
}
