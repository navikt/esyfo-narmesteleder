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
import no.nav.syfo.application.kafka.KafkaEnvironment
import no.nav.syfo.application.kafka.KafkaEventConsumer
import no.nav.syfo.application.kafka.KafkaEventLogger
import no.nav.syfo.application.kafka.KafkaListener
import no.nav.syfo.application.kafka.KafkaReason
import no.nav.syfo.application.kafka.consumerProperties
import no.nav.syfo.application.kafka.kafkaBatchDiscarded
import no.nav.syfo.application.kafka.kafkaConsumerCrashed
import no.nav.syfo.application.kafka.kafkaConsumerFailed
import no.nav.syfo.application.kafka.kafkaRecordSkippedError
import no.nav.syfo.logging.rethrowCancellation
import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Properties
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
    private val kafkaLog = KafkaEventLogger(logger, KafkaEventConsumer.SENT_SYKMELDING_PERSIST)

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
                        throw e
                    } catch (e: Exception) {
                        kafkaLog.log(
                            kafkaConsumerFailed,
                            KafkaReason.PROCESSING,
                            cause = e,
                            retryDelaySeconds = CONSUMER_JOB_DELAY_SECONDS,
                        )
                        consumer.unsubscribe()
                        delay(CONSUMER_JOB_DELAY_SECONDS.seconds)
                        consumer.subscribe(listOf(SENDT_SYKMELDING_TOPIC))
                    }
                }
            } catch (error: Error) {
                kafkaLog.log(kafkaConsumerCrashed, cause = error)
                throw error
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
            kafkaLog.log(
                kafkaRecordSkippedError,
                KafkaReason.MALFORMED_RECORD,
                cause = e,
                partition = record.partition(),
                offset = record.offset(),
            )
            null // Skip malformed records
        } catch (e: IllegalArgumentException) {
            kafkaLog.log(
                kafkaRecordSkippedError,
                KafkaReason.INVALID_KEY,
                cause = e,
                partition = record.partition(),
                offset = record.offset(),
            )
            null // Skip records with invalid UUID keys
        }
    }

    private fun handleBatchError(
        records: ConsumerRecords<String, String?>,
        kafkaConsumer: KafkaConsumer<String, String?>,
        error: Throwable
    ) {
        error.rethrowCancellation()
        if (error is Error) throw error
        if (commitOnAllErrors) {
            kafkaLog.log(kafkaBatchDiscarded, cause = error, recordCount = records.count())
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
        consumer.close(CloseOptions.timeout(Duration.ofSeconds(CLOSE_DURATION_SECONDS)))
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PersistSendtSykmeldingConsumer::class.java)
        private const val CONSUMER_JOB_DELAY_SECONDS = 30L
        private const val CLOSE_DURATION_SECONDS = 10L
        private const val POLL_DURATION_SECONDS = 1L
        private val SENDT_SYKMELDING_TOPIC = "teamsykmelding.syfo-sendt-sykmelding"

        fun kafkaConsumerProperties(env: KafkaEnvironment): Properties = consumerProperties(
            env = env,
            valueDeserializer = StringDeserializer::class,
            groupId = "esyfo-narmesteleder-persist-sendt-sykmelding-consumer"
        ).apply {
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100")
            put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "100")
        }
    }
}
