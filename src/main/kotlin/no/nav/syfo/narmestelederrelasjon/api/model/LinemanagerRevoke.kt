package no.nav.syfo.narmestelederrelasjon.api.model

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent

data class LinemanagerRevoke(
    val employeeIdentificationNumber: PersonIdent,
    val orgNumber: OrganizationNumber,
    val lastName: String,
)
