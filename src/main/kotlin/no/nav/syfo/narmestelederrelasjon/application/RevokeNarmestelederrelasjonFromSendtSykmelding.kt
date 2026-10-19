package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber

fun interface RevokeNarmestelederrelasjonFromSendtSykmelding {
    suspend fun execute(command: RevokeNarmestelederrelasjonFromSendtSykmeldingCommand)
}

data class RevokeNarmestelederrelasjonFromSendtSykmeldingCommand(
    val employeeIdent: PersonalIdentificationNumber,
    val organizationNumber: OrganizationNumber,
)
