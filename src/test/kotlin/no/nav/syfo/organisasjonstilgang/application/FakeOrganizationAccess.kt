package no.nav.syfo.organisasjonstilgang.application

import no.nav.syfo.ident.OrganizationNumber

internal class FakeOrganizationAccess(
    private val grantedOrganization: OrganizationNumber?,
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
        return if (organizationNumber == grantedOrganization) {
            result
        } else {
            OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS)
        }
    }

    fun reset() {
        result = OrganizationAccessResult.Granted(organizationName = null)
        calls.clear()
    }
}
