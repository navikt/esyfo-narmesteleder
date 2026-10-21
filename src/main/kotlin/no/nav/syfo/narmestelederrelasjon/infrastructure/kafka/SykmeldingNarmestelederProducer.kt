package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import java.time.OffsetDateTime
import java.time.ZoneOffset

interface SykmeldingNarmestelederProducer {
    fun sendSykmeldingNLRelasjon(sykmeldingNL: NlResponse, source: NlResponseSource)
    fun sendSykmldingNLBrudd(nlAvbrutt: NlAvbrutt, source: NlResponseSource)
}

class KafkaSykmeldingNarmestelederProducer(private val producer: Producer<String, NarmestelederResponseKafkaMessage>) : SykmeldingNarmestelederProducer {
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
    }
}
