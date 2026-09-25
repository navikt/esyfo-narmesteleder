package no.nav.syfo.narmesteleder.kafka

import no.nav.syfo.narmesteleder.api.v1.COUNT_FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS
import no.nav.syfo.narmesteleder.api.v1.COUNT_FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER
import no.nav.syfo.narmesteleder.api.v1.COUNT_FAILED_REVOKE_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS
import no.nav.syfo.narmesteleder.api.v1.COUNT_FAILED_REVOKE_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER
import no.nav.syfo.narmesteleder.kafka.model.KafkaMetadata
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederAvbruddResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederRelationResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.util.logger
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.time.OffsetDateTime
import java.time.ZoneOffset

interface SykmeldingNarmestelederProducer {
    fun sendSykmeldingNLRelasjon(sykmeldingNL: NlResponse, source: NlResponseSource)
    fun sendSykmldingNLBrudd(nlAvbrutt: NlAvbrutt, source: NlResponseSource)
}

class KafkaSykmeldingNarmestelederProducer(private val producer: KafkaProducer<String, NarmestelederResponseKafkaMessage>) : SykmeldingNarmestelederProducer {
    override fun sendSykmeldingNLRelasjon(sykmeldingNL: NlResponse, source: NlResponseSource) {
        val kafkaMessage =
            NarmestelederRelationResponseKafkaMessage(
                kafkaMetadata = KafkaMetadata(OffsetDateTime.now(ZoneOffset.UTC), source.source),
                nlResponse = sykmeldingNL,
            )
        try {
            producer.send(ProducerRecord(SYKEMELDING_NL_TOPIC, sykmeldingNL.orgnummer, kafkaMessage)).get()
        } catch (ex: Exception) {
            when (source) {
                NlResponseSource.LPS -> {
                    COUNT_FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS.increment()
                }

                NlResponseSource.PERSONALLEDER -> {
                    COUNT_FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER.increment()
                }

                else -> {
                }
            }
            throw ex
        }
    }

    override fun sendSykmldingNLBrudd(nlAvbrutt: NlAvbrutt, source: NlResponseSource) {
        val kafkaMessage =
            NarmestelederAvbruddResponseKafkaMessage(
                kafkaMetadata = KafkaMetadata(OffsetDateTime.now(ZoneOffset.UTC), source.source),
                nlAvbrutt = nlAvbrutt,
            )
        try {
            producer.send(ProducerRecord(SYKEMELDING_NL_TOPIC, nlAvbrutt.orgnummer, kafkaMessage)).get()
        } catch (ex: Exception) {
            when (source) {
                NlResponseSource.LPS -> {
                    COUNT_FAILED_REVOKE_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS.increment()
                }

                NlResponseSource.PERSONALLEDER -> {
                    COUNT_FAILED_REVOKE_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER.increment()
                }

                else -> {
                }
            }
            throw ex
        }
    }

    companion object {
        const val SYKEMELDING_NL_TOPIC = "teamsykmelding.syfo-narmesteleder"
        private val logger = logger()
    }
}
