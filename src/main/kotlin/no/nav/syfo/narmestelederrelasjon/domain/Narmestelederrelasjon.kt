package no.nav.syfo.narmestelederrelasjon.domain

import java.util.UUID

data class Narmestelederrelasjon(
    val id: UUID,
    val orgNumber: String,
    val employee: RelationPerson,
)
