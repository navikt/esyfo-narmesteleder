package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent

class HasActiveNarmestelederrelasjonUseCase(
    private val repository: ActiveNarmestelederrelasjonRepository
) {
    suspend fun execute(employeeIdent: PersonIdent, organizationNumber: OrganizationNumber): Boolean = repository.findActive(employeeIdent, organizationNumber).isNotEmpty()
}
