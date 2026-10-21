package no.nav.syfo.narmestelederrelasjon.application.get

import no.nav.syfo.ident.OrganizationNumber

internal class FakeNarmestelederrelasjonOrganization(
    var result: OrganizationNameResult = OrganizationNameResult.Found("Organization"),
    private val effects: MutableList<String> = mutableListOf(),
) : NarmestelederrelasjonOrganization {
    val calls = mutableListOf<OrganizationNumber>()

    override suspend fun findName(orgNumber: OrganizationNumber): OrganizationNameResult {
        calls += orgNumber
        effects += "organization"
        return result
    }

    fun reset() {
        result = OrganizationNameResult.Found("Organization")
        calls.clear()
    }
}
