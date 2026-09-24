package no.nav.syfo.narmestelederrelasjon.domain

import no.nav.syfo.ident.PersonIdent

data class RelationPerson(
    val personIdent: PersonIdent,
    val firstName: String?,
    val middleName: String?,
    val lastName: String?,
)
