package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.kafka.SykmeldingNarmestelederProducer
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocationCommand
import no.nav.syfo.narmestelederrelasjon.application.RevocationInitiator

class KafkaPublishNarmestelederrelasjonRevocationTest :
    FunSpec({
        test("publishes the legacy message and Kafka source for each initiator") {
            val producer = RecordingProducer()
            val adapter = KafkaPublishNarmestelederrelasjonRevocation(producer)
            val expectedSources = mapOf(
                RevocationInitiator.EMPLOYEE to NlResponseSource.ARBEIDSTAGER_REVOKE,
                RevocationInitiator.LINEMANAGER to NlResponseSource.NARMESTELEDER_REVOKE,
                RevocationInitiator.PERSONNEL_MANAGER to NlResponseSource.PERSONALLEDER_REVOKE,
                RevocationInitiator.LPS to NlResponseSource.LPS_REVOKE,
            )
            expectedSources.forEach { (initiator, source) ->
                adapter.publish(
                    PublishNarmestelederrelasjonRevocationCommand(
                        PersonIdent("12345678901"),
                        OrganizationNumber("123456789"),
                        initiator,
                    ),
                )
                producer.sent.count { (message, actualSource) ->
                    message.sykmeldtFnr == "12345678901" &&
                        message.orgnummer == "123456789" &&
                        actualSource == source
                } shouldBe 1
            }
            producer.sent.size shouldBe expectedSources.size
        }
    })

private class RecordingProducer : SykmeldingNarmestelederProducer {
    val sent = mutableListOf<Pair<NlAvbrutt, NlResponseSource>>()

    override fun sendSykmeldingNLRelasjon(sykmeldingNL: NlResponse, source: NlResponseSource) = error("Not used by revoke")

    override fun sendSykmldingNLBrudd(nlAvbrutt: NlAvbrutt, source: NlResponseSource) {
        sent.add(nlAvbrutt to source)
    }
}
