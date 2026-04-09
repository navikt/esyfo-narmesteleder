package no.nav.syfo.sykmelding.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.nav.syfo.application.kafka.LeaderKafkaConsumerTask
import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.slf4j.LoggerFactory

const val SENDT_SYKMELDING_TOPIC = "teamsykmelding.syfo-sendt-sykmelding"

class SendtSykmeldingKafkaConsumer(
    private val handler: SendtSykmeldingHandler,
    private val jacksonMapper: ObjectMapper,
    kafkaConsumer: KafkaConsumer<String, String>,
) : LeaderKafkaConsumerTask<String, String>(
    name = "SendtSykmeldingKafkaConsumer",
    kafkaConsumer = kafkaConsumer,
    topics = listOf(SENDT_SYKMELDING_TOPIC),
) {
    override suspend fun processRecords(records: ConsumerRecords<String, String>) {
        records.forEach { record: ConsumerRecord<String, String> ->
            logger.info("Received record with key: ${record.key()}")
            val sykmeldingMessage = record.value()
            val sendtSykmeldingKafkaMessage =
                jacksonMapper.readValue<SendtSykmeldingKafkaMessage>(sykmeldingMessage)
            handler.handleNarmestelederbehov(sendtSykmeldingKafkaMessage)
            kafkaConsumer.commitSync()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SendtSykmeldingKafkaConsumer::class.java)
    }
}
