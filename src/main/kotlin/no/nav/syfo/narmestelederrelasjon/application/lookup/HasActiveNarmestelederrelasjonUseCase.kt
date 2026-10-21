package no.nav.syfo.narmestelederrelasjon.application.lookup

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.HasActiveNarmestelederrelasjon

class HasActiveNarmestelederrelasjonUseCase(
    private val repository: ActiveNarmestelederrelasjonRepository,
) : HasActiveNarmestelederrelasjon {
    override suspend fun execute(employeeIdent: PersonalIdentificationNumber, organizationNumber: OrganizationNumber): Boolean = repository.findActive(employeeIdent, organizationNumber).isNotEmpty()
}
