package no.nav.syfo.narmestelederrelasjon.api.model

data class LinemanagerLookupRequest(
    val employeeNationalIdentificationNumber: String?,
    val organizationNumber: String?,
)
