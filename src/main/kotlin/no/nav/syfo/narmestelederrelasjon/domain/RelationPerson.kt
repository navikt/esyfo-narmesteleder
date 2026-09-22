package no.nav.syfo.narmestelederrelasjon.domain

data class RelationPerson(
    val nationalIdentificationNumber: String,
    val name: RelationPersonName?,
)
