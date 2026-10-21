package no.nav.syfo.narmestelederrelasjon.application.revoke

import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocation
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocationCommand

class RevokeNarmestelederrelasjonFromSendtSykmeldingUseCase(
    private val publishRevocation: PublishNarmestelederrelasjonRevocation,
) : RevokeNarmestelederrelasjonFromSendtSykmelding {
    override suspend fun execute(command: RevokeNarmestelederrelasjonFromSendtSykmeldingCommand) {
        publishRevocation.publish(
            PublishNarmestelederrelasjonRevocationCommand(
                employeeIdent = command.employeeIdent,
                organizationNumber = command.organizationNumber,
                initiator = RevocationInitiator.EMPLOYEE_SENDT_SYKMELDING,
            ),
        )
    }
}
