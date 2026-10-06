package no.nav.syfo.narmestelederrelasjon.api.model

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent

data class LinemanagerSearchRequest(
    val orgNumber: OrganizationNumber,
    val managerNationalIdentificationNumber: PersonIdent? = null,
    val employeeNationalIdentificationNumber: PersonIdent? = null,
    val hasActiveSickLeave: Boolean? = null,
    val text: String? = null,
    val pageSize: Int? = null,
    val pageToken: String? = null,
)
