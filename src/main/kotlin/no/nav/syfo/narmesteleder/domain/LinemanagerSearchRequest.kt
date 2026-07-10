package no.nav.syfo.narmesteleder.domain

data class LinemanagerSearchRequest(
    val orgNumber: OrganizationNumber,
    val managerNationalIdentificationNumber: PersonalIdentificationNumber? = null,
//    val text: String? = null,
    val pageSize: Int? = null,
    val pageToken: String? = null,
)

data class LinemanagerSearchQuery(
    val orgNumber: OrganizationNumber,
    val managerNationalIdentificationNumber: PersonalIdentificationNumber? = null,
    val pageSize: Int,
    val cursor: LinemanagerSearchCursor? = null,
)

data class LinemanagerSearchCursor(
    val id: Int,
)
