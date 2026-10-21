package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber

internal class FakeActiveNarmestelederrelasjonRepository(
    var rows: List<ActiveNarmestelederrelasjon> = emptyList(),
    private val effects: MutableList<String> = mutableListOf(),
) : ActiveNarmestelederrelasjonRepository {
    val lookups: MutableList<Pair<PersonalIdentificationNumber, OrganizationNumber>> = mutableListOf()

    override suspend fun findActive(
        employeeIdent: PersonalIdentificationNumber,
        organizationNumber: OrganizationNumber,
    ): List<ActiveNarmestelederrelasjon> {
        effects += "active"
        lookups.add(employeeIdent to organizationNumber)
        return rows
    }

    fun reset() {
        rows = emptyList()
        lookups.clear()
    }
}
