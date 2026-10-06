package no.nav.syfo.organisasjonstilgang.application

data class AccessibleOrganization(
    val organizationNumber: String,
    val name: String,
    val subOrganizations: List<AccessibleOrganization>,
)

fun interface AccessibleOrganizationsLookup {
    suspend fun find(subject: OrganizationAccessSubject.PersonnelManager): ListAccessibleOrganizationsResult
}

sealed interface ListAccessibleOrganizationsResult {
    data class Listed(val organizations: List<AccessibleOrganization>) : ListAccessibleOrganizationsResult
    data object Unavailable : ListAccessibleOrganizationsResult
}

class ListAccessibleOrganizationsUseCase(
    private val lookup: AccessibleOrganizationsLookup,
) {
    suspend fun execute(subject: OrganizationAccessSubject.PersonnelManager): ListAccessibleOrganizationsResult = lookup.find(subject)
}
