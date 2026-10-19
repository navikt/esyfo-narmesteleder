package no.nav.syfo.narmesteleder.domain

import no.nav.syfo.ident.PersonIdent

data class Manager(
    val nationalIdentificationNumber: PersonIdent,
    val lastName: String,
    val email: String,
    val mobile: String,
)
