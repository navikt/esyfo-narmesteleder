package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent

fun interface PublishNarmestelederrelasjonRevocation {
    suspend fun publish(command: PublishNarmestelederrelasjonRevocationCommand)
}

data class PublishNarmestelederrelasjonRevocationCommand(
    val employeeIdent: PersonIdent,
    val organizationNumber: OrganizationNumber,
    val initiator: RevocationInitiator,
)
