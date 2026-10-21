package no.nav.syfo.organisasjonstilgang.application

import no.nav.syfo.ident.OrganizationNumber

internal class FakeOrganizationAccess(
    var result: OrganizationAccessResult = OrganizationAccessResult.Granted(organizationName = null),
    private val effects: MutableList<String> = mutableListOf(),
) : OrganizationAccess {
    val calls = mutableListOf<Pair<OrganizationAccessSubject, OrganizationNumber>>()

    override suspend fun evaluate(
        subject: OrganizationAccessSubject,
        organizationNumber: OrganizationNumber,
    ): OrganizationAccessResult {
        calls += subject to organizationNumber
        effects += "access"
        return result
    }

    fun reset() {
        result = OrganizationAccessResult.Granted(organizationName = null)
        calls.clear()
    }
}
