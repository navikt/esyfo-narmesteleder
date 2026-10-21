package no.nav.syfo.organisasjonstilgang.application

internal class FakeAccessibleOrganizationsLookup(
    private val result: ListAccessibleOrganizationsResult,
) : AccessibleOrganizationsLookup {
    var subject: OrganizationAccessSubject.PersonnelManager? = null
        private set

    override suspend fun find(subject: OrganizationAccessSubject.PersonnelManager): ListAccessibleOrganizationsResult {
        this.subject = subject
        return result
    }
}
