package no.nav.syfo.narmesteleder.kafka

import no.nav.syfo.narmesteleder.api.v1.COUNT_FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS
import no.nav.syfo.narmesteleder.api.v1.COUNT_FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER
import no.nav.syfo.narmesteleder.kafka.model.INlResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.KafkaMetadata
import no.nav.syfo.narmesteleder.kafka.model.NlAvbruddResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlRelationResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.util.logger
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.time.OffsetDateTime
import java.time.ZoneOffset

interface ISykemeldingNLKafkaProducer {
    fun sendSykmeldingNLRelasjon(sykmeldingNL: NlResponse, source: NlResponseSource)
    fun sendSykmldingNLBrudd(nlAvbrutt: NlAvbrutt, source: NlResponseSource)
}

class SykemeldingNLKafkaProducer(private val producer: KafkaProducer<String, INlResponseKafkaMessage>) : ISykemeldingNLKafkaProducer {
    override fun sendSykmeldingNLRelasjon(sykmeldingNL: NlResponse, source: NlResponseSource) {
        val kafkaMessage =
            NlRelationResponseKafkaMessage(
                kafkaMetadata = KafkaMetadata(OffsetDateTime.now(ZoneOffset.UTC), source.source),
                nlResponse = sykmeldingNL,
            )
        try {
            producer.send(ProducerRecord(SYKEMELDING_NL_TOPIC, sykmeldingNL.orgnummer, kafkaMessage)).get()
        } catch (ex: Exception) {
            when(source){
                NlResponseSource.LPS -> {
                    COUNT_FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS.increment()
                //dsfds    // Metrics increment can be added here if needed
                }
                NlResponseSource.PERSONALLEDER -> {
                    COUNT_FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER.increment()
                // Metrics increment can be added here if needed
                }
                else -> {
                }
            }
            logger.error("Exception was thrown when attempting to send NlRelasjon: ${ex.message}")
            throw ex
        }
    }

    override fun sendSykmldingNLBrudd(nlAvbrutt: NlAvbrutt, source: NlResponseSource) {
        val kafkaMessage =
            NlAvbruddResponseKafkaMessage(
                kafkaMetadata = KafkaMetadata(OffsetDateTime.now(ZoneOffset.UTC), source.source),
                nlAvbrutt = nlAvbrutt,
            )
        try {
            producer.send(ProducerRecord(SYKEMELDING_NL_TOPIC, nlAvbrutt.orgnummer, kafkaMessage)).get()
        } catch (ex: Exception) {
            logger.error("Exception was thrown when attempting to send NlAvbrutt: ${ex.message}")
            throw ex
        }
    }

    companion object {
        const val SYKEMELDING_NL_TOPIC = "teamsykmelding.syfo-narmesteleder"
        private val logger = logger()
    }
}
