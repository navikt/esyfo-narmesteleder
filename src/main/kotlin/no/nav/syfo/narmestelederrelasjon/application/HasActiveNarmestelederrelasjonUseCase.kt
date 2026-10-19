package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber

class HasActiveNarmestelederrelasjonUseCase(
    private val repository: ActiveNarmestelederrelasjonRepository,
) : HasActiveNarmestelederrelasjon {
    override suspend fun execute(employeeIdent: PersonalIdentificationNumber, organizationNumber: OrganizationNumber): Boolean = repository.findActive(employeeIdent, organizationNumber).isNotEmpty()
}
