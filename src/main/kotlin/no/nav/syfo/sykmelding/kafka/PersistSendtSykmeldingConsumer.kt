package no.nav.syfo.sykmelding.kafka

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.kafka.KafkaListener
import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import no.nav.syfo.sykmelding.service.SykmeldingRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class PersistSendtSykmeldingConsumer(
    private val handler: SendtSykmeldingHandler,
    private val jacksonMapper: ObjectMapper,
    private val kafkaConsumer: KafkaConsumer<String, String?>,
    private val scope: CoroutineScope,
    private val env: OtherEnvironmentProperties
) : KafkaListener {
    private lateinit var job: Job
    var commitOnAllErrors = false

    override fun listen() {
        if (!env.persistSendtSykmelding) {
            logger.info("Persisting of sendt sykmelding is disabled, not starting consumer for $SENDT_SYKMELDING_TOPIC")
            return
        }

        logger.info("Starting persist $SENDT_SYKMELDING_TOPIC consumer")
        job = scope.launch(Dispatchers.IO + CoroutineName("persist-sendt-sykmelding-consumer")) {
            kafkaConsumer.subscribe(listOf(SENDT_SYKMELDING_TOPIC))
            while (isActive) {
                try {
                    val records = kafkaConsumer.poll(Duration.ofSeconds(POLL_DURATION_SECONDS))
                    if (!records.isEmpty) {
                        processBatch(records)
                    }
                } catch (_: WakeupException) {
                    logger.info("Waked Kafka consumer")
                } catch (e: Exception) {
                    logger.error(
                        "Error running kafka consumer. Waiting $DELAY_ON_ERROR_SECONDS seconds for retry.",
                        e
                    )
                    kafkaConsumer.unsubscribe()
                    delay(DELAY_ON_ERROR_SECONDS.seconds)
                    kafkaConsumer.subscribe(listOf(SENDT_SYKMELDING_TOPIC))
                }
            }
            kafkaConsumer.close()
            logger.info("Exited $SENDT_SYKMELDING_TOPIC consumer loop")
        }
    }

    private suspend fun processBatch(records: ConsumerRecords<String, String?>) {
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
            null // Skip malformed records
        } catch (e: IllegalArgumentException) {
            logger.error(
                "Invalid UUID format for key ${record.key()} at offset ${record.offset()}. Skipping record.",
                e
            )
            null // Skip records with invalid UUID keys
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

    override suspend fun stop() {
        if (!::job.isInitialized) error("persist $SENDT_SYKMELDING_TOPIC consumer not started!")

        logger.info("Preparing shutdown")
        logger.info("Stopping consuming topic $SENDT_SYKMELDING_TOPIC")

        job.cancel()
        kafkaConsumer.wakeup()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SendtSykmeldingKafkaConsumer::class.java)
        private const val DELAY_ON_ERROR_SECONDS = 30L
        private const val POLL_DURATION_SECONDS = 1L
        private val SENDT_SYKMELDING_TOPIC = "teamsykmelding.syfo-sendt-sykmelding"
    }
}
