package no.nav.syfo.narmestelederrelasjon.domain

import no.nav.syfo.ident.OrganizationNumber
import java.util.UUID

data class Narmestelederrelasjon(
    val id: UUID,
    val orgNumber: OrganizationNumber,
    val employee: RelationPerson,
)
