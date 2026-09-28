package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent

internal class FakeActiveNarmestelederrelasjonRepository : ActiveNarmestelederrelasjonRepository {
    var rows: List<ActiveNarmestelederrelasjon> = emptyList()
    val lookups: MutableList<Pair<PersonIdent, OrganizationNumber>> = mutableListOf()

    override suspend fun findActive(
        employeeIdent: PersonIdent,
        organizationNumber: OrganizationNumber,
    ): List<ActiveNarmestelederrelasjon> {
        lookups.add(employeeIdent to organizationNumber)
        return rows
    }

    fun reset() {
        rows = emptyList()
        lookups.clear()
    }
}
