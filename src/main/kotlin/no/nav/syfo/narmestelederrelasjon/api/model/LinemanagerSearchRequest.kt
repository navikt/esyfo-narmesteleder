package no.nav.syfo.narmestelederrelasjon.api.model

data class LinemanagerSearchRequest(
    val orgNumber: String,
    val managerNationalIdentificationNumber: String? = null,
    val employeeNationalIdentificationNumber: String? = null,
    val hasActiveSickLeave: Boolean? = null,
    val text: String? = null,
    val pageSize: Int? = null,
    val pageToken: String? = null,
)
