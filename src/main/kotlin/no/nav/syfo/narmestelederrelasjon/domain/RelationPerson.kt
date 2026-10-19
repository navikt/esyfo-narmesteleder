package no.nav.syfo.narmestelederrelasjon.domain

import no.nav.syfo.ident.PersonalIdentificationNumber

data class RelationPerson(
    val personIdent: PersonalIdentificationNumber,
    val firstName: String?,
    val middleName: String?,
    val lastName: String?,
)
