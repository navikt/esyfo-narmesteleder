package no.nav.syfo.narmestelederbehov.api

import no.nav.syfo.ident.PersonalIdentificationNumber

data class Manager(
    val nationalIdentificationNumber: PersonalIdentificationNumber,
    val lastName: String,
    val email: String,
    val mobile: String,
)
