package no.nav.syfo.narmestelederrelasjon.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocation
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocationCommand
import no.nav.syfo.narmestelederrelasjon.application.RevocationInitiator
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlAvbrutt
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlResponseSource
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.SykmeldingNarmestelederProducer
import no.nav.syfo.platform.kafka.PRODUCER_MAX_SEND_DURATION

class KafkaPublishNarmestelederrelasjonRevocation(
    private val producer: SykmeldingNarmestelederProducer,
) : PublishNarmestelederrelasjonRevocation {
    override val maxPublishDuration = PRODUCER_MAX_SEND_DURATION

    override suspend fun publish(command: PublishNarmestelederrelasjonRevocationCommand): Unit = withContext(Dispatchers.IO) {
        producer.sendSykmldingNLBrudd(
            NlAvbrutt(
                sykmeldtFnr = command.employeeIdent.value,
                orgnummer = command.organizationNumber.value,
            ),
            source = when (command.initiator) {
                RevocationInitiator.EMPLOYEE -> NlResponseSource.ARBEIDSTAGER_REVOKE
                RevocationInitiator.LINEMANAGER -> NlResponseSource.NARMESTELEDER_REVOKE
                RevocationInitiator.PERSONNEL_MANAGER -> NlResponseSource.PERSONALLEDER_REVOKE
                RevocationInitiator.LPS -> NlResponseSource.LPS_REVOKE
                RevocationInitiator.EMPLOYEE_SENDT_SYKMELDING -> NlResponseSource.ARBEIDSTAGER_SYKMELDING_REVOKE
                RevocationInitiator.EMPLOYMENT_ENDED -> NlResponseSource.ARBEIDSFORHOLD_REVOKE
            },
        )
    }
}
