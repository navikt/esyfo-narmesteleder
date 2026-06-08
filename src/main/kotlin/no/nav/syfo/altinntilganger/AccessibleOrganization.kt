package no.nav.syfo.altinntilganger

data class AccessibleOrganizationsResponse(
    val organizations: List<AccessibleOrganization>,
)

data class AccessibleOrganization(
    val orgNumber: String,
    val name: String,
    val subOrganizations: List<AccessibleOrganization>,
)
