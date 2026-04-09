package no.nav.syfo.narmesteleder.kafka

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.nav.syfo.application.kafka.LeaderKafkaConsumerTask
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import no.nav.syfo.narmesteleder.service.BehovSource
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory

const val SYKMELDING_NL_TOPIC = "teamsykmelding.syfo-narmesteleder-leesah"

class LeesahNLKafkaConsumer(
    kafkaConsumer: KafkaConsumer<String, String>,
    private val jacksonMapper: ObjectMapper,
    private val handler: NlBehovLeesahHandler,
    private val commitOnAllErrors: Boolean = false,
) : LeaderKafkaConsumerTask<String, String>(
    name = "LeesahNLKafkaConsumer",
    kafkaConsumer = kafkaConsumer,
    topics = listOf(SYKMELDING_NL_TOPIC),
) {
    private val processed = mutableMapOf<TopicPartition, Long>()

    override suspend fun processRecords(records: ConsumerRecords<String, String>) {
        records.forEach { record: ConsumerRecord<String, String> ->
            logger.info("Received record with key: ${record.key()}")
            processRecord(record)
        }
        commitProcessedSync()
    }

    private suspend fun processRecord(record: ConsumerRecord<String, String>) {
        runCatching {
            val nlKafkaMessage =
                jacksonMapper.readValue<NarmestelederLeesahKafkaMessage>(record.value())

            logger.info("Processing NL message with id: ${nlKafkaMessage.narmesteLederId}")
            if (nlKafkaMessage.aktivTom == null) {
                handler.updateStatusForRequirement(nlKafkaMessage)
            } else {
                handler.handleByLeesahStatus(
                    nlKafkaMessage.toNlBehovWrite(),
                    nlKafkaMessage.status,
                    behovSource = BehovSource(
                        nlKafkaMessage.narmesteLederId.toString(),
                        source = SYKMELDING_NL_TOPIC
                    )
                )
            }
            addToProcessed(record)
        }.getOrElse {
            handleProcessingError(record, it)
        }
    }

    private fun commitProcessedSync() {
        if (processed.isEmpty()) return

        val toCommit = processed.mapValues { (_, off) -> OffsetAndMetadata(off + 1) }
        if (toCommit.isNotEmpty()) kafkaConsumer.commitSync(toCommit)

        processed.clear()
        logger.info("Committed offsets for partitions")
    }

    private fun addToProcessed(record: ConsumerRecord<String, String>) {
        processed[TopicPartition(record.topic(), record.partition())] = record.offset()
    }

    private fun handleProcessingError(
        record: ConsumerRecord<String, String>,
        error: Throwable
    ) {
        when (error) {
            is JsonMappingException -> {
                logger.error(
                    "Error while deserializing record with key ${record.key()} and offset ${record.offset()}. "
                )
            }

            else -> {
                logger.error(
                    "Error while processing record with key ${record.key()} " +
                        "and offset ${record.offset()}. Will NOT ack, message will be retried.",
                    error
                )
            }
        }
        if (commitOnAllErrors) {
            logger.info("commitOnAllErrors is enabled, committing offset despite the error.")
            addToProcessed(record)
        }
        throw error
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LeesahNLKafkaConsumer::class.java)
    }
}
