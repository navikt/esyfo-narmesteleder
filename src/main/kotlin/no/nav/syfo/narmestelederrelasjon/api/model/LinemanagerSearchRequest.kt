package no.nav.syfo.narmestelederrelasjon.api.model

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber

data class LinemanagerSearchRequest(
    val orgNumber: OrganizationNumber,
    val managerNationalIdentificationNumber: PersonalIdentificationNumber? = null,
    val employeeNationalIdentificationNumber: PersonalIdentificationNumber? = null,
    val hasActiveSickLeave: Boolean? = null,
    val text: String? = null,
    val pageSize: Int? = null,
    val pageToken: String? = null,
)
