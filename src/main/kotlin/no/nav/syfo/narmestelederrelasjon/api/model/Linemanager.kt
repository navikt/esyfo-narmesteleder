package no.nav.syfo.narmestelederrelasjon.api.model

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber

data class Linemanager(
    val employeeIdentificationNumber: PersonalIdentificationNumber,
    val lastName: String,
    val orgNumber: OrganizationNumber,
    val manager: Manager,
)
