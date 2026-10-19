package no.nav.syfo.narmestelederrelasjon.api.model

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber

data class LinemanagerRevoke(
    val employeeIdentificationNumber: PersonalIdentificationNumber,
    val orgNumber: OrganizationNumber,
    val lastName: String,
)
