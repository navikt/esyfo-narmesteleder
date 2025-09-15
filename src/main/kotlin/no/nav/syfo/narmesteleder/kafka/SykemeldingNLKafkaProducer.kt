package no.nav.syfo.narmesteleder.kafka

import java.time.OffsetDateTime
import java.time.ZoneOffset
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

interface ISykemeldingNLKafkaProducer {
    fun sendSykemeldingNLRelasjon(sykemeldingNL: NlResponse, source: NlResponseSource)
    fun sendSykemeldingNLBrudd(nlAvbrutt: NlAvbrutt, source: NlResponseSource)
}

class SykemeldingNLKafkaProducer(private val producer: KafkaProducer<String, INlResponseKafkaMessage>) :
    ISykemeldingNLKafkaProducer {
    override fun sendSykemeldingNLRelasjon(sykemeldingNL: NlResponse, source: NlResponseSource) {
        val kafkaMessage =
            NlRelationResponseKafkaMessage(
                kafkaMetadata = KafkaMetadata(OffsetDateTime.now(ZoneOffset.UTC), source.name),
                nlResponse = sykemeldingNL,
            )
        try {
            producer.send(ProducerRecord(SYKEMELDING_NL_TOPIC, sykemeldingNL.orgnummer, kafkaMessage)).get()
        } catch (ex: Exception) {
            logger.error("Exception was thrown when attempting to send NlRelasjon: ${ex.message}")
            throw ex
        }
    }

    override fun sendSykemeldingNLBrudd(nlAvbrutt: NlAvbrutt, source: NlResponseSource) {
        val kafkaMessage =
            NlAvbruddResponseKafkaMessage(
                kafkaMetadata = KafkaMetadata(OffsetDateTime.now(ZoneOffset.UTC), source.name),
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
