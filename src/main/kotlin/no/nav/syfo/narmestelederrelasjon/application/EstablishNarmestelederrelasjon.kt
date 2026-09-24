package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederrelasjon.domain.RelationManager
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource

fun interface EstablishNarmestelederrelasjon {
    suspend fun establish(command: EstablishNarmestelederrelasjonCommand)
}

data class EstablishNarmestelederrelasjonCommand(
    val employee: RelationPerson,
    val manager: RelationManager,
    val organizationNumber: OrganizationNumber,
    val source: RelationSource,
)
