package no.nav.syfo.sykmelding.kafka

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.nav.syfo.application.kafka.LeaderKafkaConsumerTask
import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class PersistSendtSykmeldingConsumer(
    private val handler: SendtSykmeldingHandler,
    private val jacksonMapper: ObjectMapper,
    kafkaConsumer: KafkaConsumer<String, String?>,
    private val commitOnAllErrors: Boolean = false,
) : LeaderKafkaConsumerTask<String, String?>(
    name = "PersistSendtSykmeldingConsumer",
    kafkaConsumer = kafkaConsumer,
    topics = listOf(SENDT_SYKMELDING_TOPIC),
    retryDelay = CONSUMER_RETRY_DELAY,
) {
    override suspend fun processRecords(records: ConsumerRecords<String, String?>) {
        runCatching {
            val sykmeldingRecords = deserializeRecords(records)
            handler.handleSykmeldingBatch(sykmeldingRecords)
            kafkaConsumer.commitSync()
        }.getOrElse { error ->
            handleBatchError(records, error)
        }
    }

    private fun deserializeRecords(records: ConsumerRecords<String, String?>): List<SykmeldingRecord> = records.mapNotNull { record ->
        try {
            val sykmeldingId = UUID.fromString(record.key())
            val message = record.value()?.let {
                jacksonMapper.readValue<SendtSykmeldingKafkaMessage>(it)
            }
            SykmeldingRecord(
                offset = record.offset(),
                sykmeldingId = sykmeldingId,
                message = message
            )
        } catch (e: JsonMappingException) {
            logger.error(
                "Error while deserializing record with key ${record.key()} and offset ${record.offset()}. Skipping record.",
                e
            )
            null
        } catch (e: IllegalArgumentException) {
            logger.error(
                "Invalid UUID format for key ${record.key()} at offset ${record.offset()}. Skipping record.",
                e
            )
            null
        }
    }

    private fun handleBatchError(
        records: ConsumerRecords<String, String?>,
        error: Throwable
    ) {
        logger.error(
            "Error while processing batch of ${records.count()} records. " +
                "Entire batch will be retried on next poll.",
            error
        )

        if (commitOnAllErrors) {
            logger.info("commitOnAllErrors is enabled, committing offsets despite the error.")
            kafkaConsumer.commitSync()
        } else {
            throw error
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PersistSendtSykmeldingConsumer::class.java)
        private val CONSUMER_RETRY_DELAY = 30.seconds
    }
}
