package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import kotlin.time.Duration

interface PublishNarmestelederrelasjonRevocation {
    /** How long [publish] may take before it returns or fails, so a caller holding a lease can tell whether it fits. */
    val maxPublishDuration: Duration

    suspend fun publish(command: PublishNarmestelederrelasjonRevocationCommand)
}

data class PublishNarmestelederrelasjonRevocationCommand(
    val employeeIdent: PersonIdent,
    val organizationNumber: OrganizationNumber,
    val initiator: RevocationInitiator,
)
