package no.nav.syfo.narmestelederrelasjon.api.model

import no.nav.syfo.ident.PersonalIdentificationNumber

data class Manager(
    val nationalIdentificationNumber: PersonalIdentificationNumber,
    val lastName: String,
    val email: String,
    val mobile: String,
)
