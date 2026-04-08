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
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class PersistSendtSykmeldingConsumer(
    private val handler: SendtSykmeldingHandler,
    private val jacksonMapper: ObjectMapper,
    private val kafkaConsumerFactory: () -> KafkaConsumer<String, String?>,
    private val scope: CoroutineScope,
    private val env: OtherEnvironmentProperties,
) : KafkaListener,
    AutoCloseable {
    constructor(
        handler: SendtSykmeldingHandler,
        jacksonMapper: ObjectMapper,
        kafkaConsumer: KafkaConsumer<String, String?>,
        scope: CoroutineScope,
        env: OtherEnvironmentProperties,
    ) : this(
        handler = handler,
        jacksonMapper = jacksonMapper,
        kafkaConsumerFactory = { kafkaConsumer },
        scope = scope,
        env = env,
    )

    private var job: Job? = null

    @Volatile
    private var kafkaConsumer: KafkaConsumer<String, String?>? = null
    var commitOnAllErrors = false

    override fun listen() {
        if (!env.persistSendtSykmelding) {
            logger.info("Persisting of sendt sykmelding is disabled, not starting consumer for $SENDT_SYKMELDING_TOPIC")
            return
        }

        if (job?.isActive == true) {
            logger.info("Persist consumer for {} is already running", SENDT_SYKMELDING_TOPIC)
            return
        }

        val consumer = kafkaConsumerFactory()
        kafkaConsumer = consumer

        logger.info("Starting persist $SENDT_SYKMELDING_TOPIC consumer")
        job = scope.launch(Dispatchers.IO + CoroutineName("persist-sendt-sykmelding-consumer")) {
            try {
                consumer.subscribe(listOf(SENDT_SYKMELDING_TOPIC))

                while (isActive) {
                    try {
                        val records = consumer.poll(Duration.ofSeconds(POLL_DURATION_SECONDS))
                        if (!records.isEmpty) {
                            processBatch(records, consumer)
                        }
                    } catch (_: WakeupException) {
                        logger.info("Waked Kafka consumer")
                        break
                    } catch (e: CancellationException) {
                        break
                    } catch (e: Exception) {
                        logger.error(
                            "Error running kafka consumer. Waiting $CONSUMER_JOB_DELAY_SECONDS seconds for retry.",
                            e
                        )
                        consumer.unsubscribe()
                        delay(CONSUMER_JOB_DELAY_SECONDS.seconds)
                        consumer.subscribe(listOf(SENDT_SYKMELDING_TOPIC))
                    }
                }
            } finally {
                closeKafkaConsumer(consumer)
                if (kafkaConsumer === consumer) {
                    kafkaConsumer = null
                }
                job = null
                logger.info("Exited $SENDT_SYKMELDING_TOPIC consumer loop")
            }
        }
    }

    private suspend fun processBatch(
        records: ConsumerRecords<String, String?>,
        kafkaConsumer: KafkaConsumer<String, String?>,
    ) {
        runCatching {
            val sykmeldingRecords = deserializeRecords(records)
            handler.handleSykmeldingBatch(sykmeldingRecords)
            kafkaConsumer.commitSync()
        }.getOrElse { error ->
            handleBatchError(records, kafkaConsumer, error)
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
        kafkaConsumer: KafkaConsumer<String, String?>,
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

    override fun close() {
        kafkaConsumer?.let { consumer ->
            closeKafkaConsumer(consumer)
            if (kafkaConsumer === consumer) {
                kafkaConsumer = null
            }
        }
    }

    override suspend fun stop() {
        val currentJob = job
        val currentConsumer = kafkaConsumer
        if (currentJob == null || !currentJob.isActive) {
            logger.info("Persist consumer for {} is already stopped", SENDT_SYKMELDING_TOPIC)
            return
        }

        logger.info("Preparing shutdown")
        logger.info("Stopping consuming topic $SENDT_SYKMELDING_TOPIC")

        currentJob.cancel()
        currentConsumer?.wakeup()
        currentJob.join()
    }

    private fun closeKafkaConsumer(consumer: KafkaConsumer<String, String?>) {
        logger.info("Closing Kafka consumer")
        consumer.unsubscribe()
        consumer.close(Duration.ofSeconds(CLOSE_DURATION_SECONDS))
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PersistSendtSykmeldingConsumer::class.java)
        private const val CONSUMER_JOB_DELAY_SECONDS = 30L
        private const val CLOSE_DURATION_SECONDS = 10L
        private const val POLL_DURATION_SECONDS = 1L
        private val SENDT_SYKMELDING_TOPIC = "teamsykmelding.syfo-sendt-sykmelding"
    }
}
