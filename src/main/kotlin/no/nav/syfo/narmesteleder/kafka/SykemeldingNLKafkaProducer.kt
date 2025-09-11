package no.nav.syfo.narmesteleder.kafka

import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import no.nav.syfo.narmesteleder.kafka.model.INlResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.KafkaMetadata
import no.nav.syfo.narmesteleder.kafka.model.NlAvbruddResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlRelationResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.util.logger
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

class FakeSykemeldingNLKafkaProducer : ISykemeldingNLKafkaProducer {
    val logger = logger()
    override fun sendSykemeldingNLRelasjon(sykemeldingNL: NlResponse) {
        logger.info("FakeSykemeldingNLKafkaProducer sendSykemeldingNLRelasjon to orgnummer: ${sykemeldingNL.orgnummer}")
        logger.info(sykemeldingNL.hashFnrFields().toString())
    }

    override fun sendSykemeldingNLBrudd(nlAvbrutt: NlAvbrutt) {
        logger.info("FakeSykemeldingNLKafkaProducer sendSykemeldingNLBrudd to orgnummer: ${nlAvbrutt.orgnummer}")
        logger.info(nlAvbrutt.copy(sykmeldtFnr = nlAvbrutt.sykmeldtFnr.sha256()).toString())
    }

    fun NlResponse.hashFnrFields(): NlResponse {
        return this.copy(
            leder = this.leder.copy(fnr = this.leder.fnr.sha256()),
            sykmeldt = this.sykmeldt.copy(fnr = this.sykmeldt.fnr.sha256())
        )
    }

    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(this.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
