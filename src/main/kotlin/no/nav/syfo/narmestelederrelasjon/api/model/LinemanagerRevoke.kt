package no.nav.syfo.narmestelederrelasjon.api.model

import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber

data class LinemanagerRevoke(
    val employeeIdentificationNumber: PersonalIdentificationNumber,
    val orgNumber: OrganizationNumber,
    val lastName: String,
)
