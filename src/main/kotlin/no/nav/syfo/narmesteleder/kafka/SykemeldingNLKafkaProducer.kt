package no.nav.syfo.narmesteleder.kafka

import java.time.OffsetDateTime
import java.time.ZoneOffset
import no.nav.syfo.narmesteleder.kafka.model.INlResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.KafkaMetadata
import no.nav.syfo.narmesteleder.kafka.model.NlAvbruddResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlRelationResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord

interface ISykemeldingNLKafkaProducer {
    fun sendSykemeldingNLRelasjon(sykemeldingNL: NlResponse)
    fun sendSykemeldingNLBrudd(nlAvbrutt: NlAvbrutt)
}

class SykemeldingNLKafkaProducer(private val producer: KafkaProducer<String, INlResponseKafkaMessage>) :
    ISykemeldingNLKafkaProducer {
    override fun sendSykemeldingNLRelasjon(sykemeldingNL: NlResponse) {
        val kafkaMessage =
            NlRelationResponseKafkaMessage(
                kafkaMetadata = KafkaMetadata(
                    OffsetDateTime.now(ZoneOffset.UTC), PRODUCER_SOURCE
                ),
                nlResponse = sykemeldingNL,
            )
        producer.send(ProducerRecord(SYKEMELDING_NL_TOPIC, sykemeldingNL.orgnummer, kafkaMessage)).get()
    }

    override fun sendSykemeldingNLBrudd(nlAvbrutt: NlAvbrutt) {
        val kafkaMessage =
            NlAvbruddResponseKafkaMessage(
                kafkaMetadata = KafkaMetadata(
                    OffsetDateTime.now(ZoneOffset.UTC), PRODUCER_SOURCE
                ),
                nlAvbrutt = nlAvbrutt,
            )
        producer.send(ProducerRecord(SYKEMELDING_NL_TOPIC, nlAvbrutt.orgnummer, kafkaMessage)).get()
    }

    companion object {
        const val SYKEMELDING_NL_TOPIC = "teamsykmelding.syfo-narmesteleder"
        const val PRODUCER_SOURCE = "syfo-narmesteleder"
    }
}
