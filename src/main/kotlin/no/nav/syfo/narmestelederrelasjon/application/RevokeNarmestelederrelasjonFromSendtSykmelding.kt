package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent

fun interface RevokeNarmestelederrelasjonFromSendtSykmelding {
    suspend fun execute(command: RevokeNarmestelederrelasjonFromSendtSykmeldingCommand)
}

data class RevokeNarmestelederrelasjonFromSendtSykmeldingCommand(
    val employeeIdent: PersonIdent,
    val organizationNumber: OrganizationNumber,
)
