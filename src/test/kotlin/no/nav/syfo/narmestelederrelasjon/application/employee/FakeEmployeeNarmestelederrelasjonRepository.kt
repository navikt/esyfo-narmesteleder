package no.nav.syfo.narmestelederrelasjon.application.employee

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber

internal class FakeEmployeeNarmestelederrelasjonRepository : EmployeeNarmestelederrelasjonRepository {
    var rows = emptyList<EmployeeNarmestelederrelasjon>()
    val lookups = mutableListOf<Pair<PersonalIdentificationNumber, OrganizationNumber?>>()

    override suspend fun findActive(employeeIdent: PersonalIdentificationNumber, organizationNumber: OrganizationNumber?): List<EmployeeNarmestelederrelasjon> {
        lookups.add(employeeIdent to organizationNumber)
        return rows
    }

    fun reset() {
        rows = emptyList()
        lookups.clear()
    }
}
