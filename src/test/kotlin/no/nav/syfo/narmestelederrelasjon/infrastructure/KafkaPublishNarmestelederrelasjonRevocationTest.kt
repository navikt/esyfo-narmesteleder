package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocationCommand
import no.nav.syfo.narmestelederrelasjon.application.RevocationInitiator
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlAvbrutt
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlResponse
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlResponseSource
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.SykmeldingNarmestelederProducer

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
                RevocationInitiator.EMPLOYEE_SENDT_SYKMELDING to NlResponseSource.ARBEIDSTAGER_SYKMELDING_REVOKE,
                RevocationInitiator.EMPLOYMENT_ENDED to NlResponseSource.ARBEIDSFORHOLD_REVOKE,
            )
            expectedSources.keys shouldBe RevocationInitiator.entries.toSet()
            NlResponseSource.ARBEIDSFORHOLD_REVOKE.source shouldBe "narmesteleder-arbeidsforhold"
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

        test("runs the blocking producer off the caller's non-IO dispatcher and waits for it to finish") {
            runTest {
                val callerThread = Thread.currentThread()
                val producer = RecordingProducer()
                val adapter = KafkaPublishNarmestelederrelasjonRevocation(producer)

                adapter.publish(
                    PublishNarmestelederrelasjonRevocationCommand(
                        PersonIdent("12345678901"),
                        OrganizationNumber("123456789"),
                        RevocationInitiator.EMPLOYMENT_ENDED,
                    ),
                )

                producer.sendThread shouldNotBe null
                producer.sendThread shouldNotBe callerThread
                producer.sent.size shouldBe 1
            }
        }
    })

private class RecordingProducer : SykmeldingNarmestelederProducer {
    val sent = mutableListOf<Pair<NlAvbrutt, NlResponseSource>>()
    var sendThread: Thread? = null

    override fun sendSykmeldingNLRelasjon(sykmeldingNL: NlResponse, source: NlResponseSource) = error("Not used by revoke")

    override fun sendSykmldingNLBrudd(nlAvbrutt: NlAvbrutt, source: NlResponseSource) {
        sendThread = Thread.currentThread()
        sent.add(nlAvbrutt to source)
    }
}
