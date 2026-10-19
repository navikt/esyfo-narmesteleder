package no.nav.syfo.narmesteleder.domain

import no.nav.syfo.ident.PersonalIdentificationNumber

data class Manager(
    val nationalIdentificationNumber: PersonalIdentificationNumber,
    val lastName: String,
    val email: String,
    val mobile: String,
)
