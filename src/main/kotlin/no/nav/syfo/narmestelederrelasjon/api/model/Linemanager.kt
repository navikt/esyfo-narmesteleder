package no.nav.syfo.narmestelederrelasjon.api.model

import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber

data class Linemanager(
    val employeeIdentificationNumber: PersonalIdentificationNumber,
    val lastName: String,
    val orgNumber: OrganizationNumber,
    val manager: Manager,
)
