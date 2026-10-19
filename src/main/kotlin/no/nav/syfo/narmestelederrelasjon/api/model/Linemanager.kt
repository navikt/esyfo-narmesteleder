package no.nav.syfo.narmestelederrelasjon.api.model

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.Manager

data class Linemanager(
    val employeeIdentificationNumber: PersonIdent,
    val lastName: String,
    val orgNumber: OrganizationNumber,
    val manager: Manager,
)
