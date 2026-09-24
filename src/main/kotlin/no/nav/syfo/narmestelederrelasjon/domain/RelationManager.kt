package no.nav.syfo.narmestelederrelasjon.domain

import no.nav.syfo.ident.PersonIdent

data class RelationManager(
    val personIdent: PersonIdent,
    val firstName: String,
    val middleName: String?,
    val lastName: String,
    val email: String,
    val mobile: String,
)
