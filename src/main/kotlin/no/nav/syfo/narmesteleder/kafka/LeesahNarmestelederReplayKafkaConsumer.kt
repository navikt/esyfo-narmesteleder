package no.nav.syfo.narmesteleder.kafka

import com.fasterxml.jackson.core.JsonProcessingException
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
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import no.nav.syfo.narmesteleder.service.NarmestelederRegisterService
import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

data class LeesahNarmestelederRecord(
    val offset: Long,
    val message: NarmestelederLeesahKafkaMessage,
)

class LeesahNarmestelederReplayKafkaConsumer(
    private val handler: NarmestelederRegisterService,
    private val jacksonMapper: ObjectMapper,
    private val kafkaConsumerFactory: () -> KafkaConsumer<String, String?>,
    private val scope: CoroutineScope,
    private val env: OtherEnvironmentProperties,
) : KafkaListener,
    AutoCloseable {
    constructor(
        handler: NarmestelederRegisterService,
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
        if (!env.persistNarmestelederRegister) {
            logger.info("Persisting of narmesteleder from leesah topic is disabled, not starting consumer for $TEAMSYKMELDING_NL_LEESAH_TOPIC")
            return
        }
        if (job?.isActive == true) {
            logger.info("Replay consumer for {} is already running", TEAMSYKMELDING_NL_LEESAH_TOPIC)
            return
        }

        val consumer = kafkaConsumerFactory()
        kafkaConsumer = consumer

        logger.info("Starting replay consumer for {}", TEAMSYKMELDING_NL_LEESAH_TOPIC)
        job = scope.launch(Dispatchers.IO + CoroutineName("leesah-narmesteleder-replay-consumer")) {
            try {
                consumer.subscribe(listOf(TEAMSYKMELDING_NL_LEESAH_TOPIC))

                while (isActive) {
                    try {
                        val records = consumer.poll(Duration.ofSeconds(POLL_DURATION_SECONDS))
                        if (!records.isEmpty) {
                            processBatch(records, consumer)
                        }
                    } catch (_: WakeupException) {
                        logger.info("Wakeup received for replay consumer")
                        break
                    } catch (_: CancellationException) {
                        break
                    } catch (e: Exception) {
                        logger.error(
                            "Error running replay consumer for {}. Waiting {} seconds for retry.",
                            TEAMSYKMELDING_NL_LEESAH_TOPIC,
                            CONSUMER_JOB_DELAY_SECONDS,
                            e
                        )
                        consumer.unsubscribe()
                        delay(CONSUMER_JOB_DELAY_SECONDS.seconds)
                        consumer.subscribe(listOf(TEAMSYKMELDING_NL_LEESAH_TOPIC))
                    }
                }
            } finally {
                closeKafkaConsumer(consumer)
                if (kafkaConsumer === consumer) {
                    kafkaConsumer = null
                }
                job = null
                logger.info("Exited replay consumer loop for {}", TEAMSYKMELDING_NL_LEESAH_TOPIC)
            }
        }
    }

    internal fun deserializeRecords(records: ConsumerRecords<String, String?>): List<LeesahNarmestelederRecord> = records.mapNotNull { record ->
        try {
            val value = record.value()
            if (value == null) {
                logger.warn(
                    "Skipping tombstone record from {} at offset {}",
                    record.topic(),
                    record.offset()
                )
                null
            } else {
                LeesahNarmestelederRecord(
                    offset = record.offset(),
                    message = jacksonMapper.readValue<NarmestelederLeesahKafkaMessage>(value),
                )
            }
        } catch (e: JsonProcessingException) {
            logger.error(
                "Error while deserializing record from {} at offset {}. Skipping record.",
                record.topic(),
                record.offset(),
                e
            )
            null
        }
    }

    internal fun processBatch(
        records: ConsumerRecords<String, String?>,
        kafkaConsumer: KafkaConsumer<String, String?> = requireNotNull(this.kafkaConsumer) {
            "Replay consumer is not started"
        },
    ) {
        runCatching {
            val leesahRecords = deserializeRecords(records)
            handler.upsertBatch(leesahRecords)
            handler.insertPersons(leesahRecords)
            kafkaConsumer.commitSync()
        }.getOrElse { error ->
            handleBatchError(records, kafkaConsumer, error)
        }
    }

    private fun handleBatchError(
        records: ConsumerRecords<String, String?>,
        kafkaConsumer: KafkaConsumer<String, String?>,
        error: Throwable,
    ) {
        logger.error(
            "Error while processing replay batch of {} records from {}. Entire batch will be retried on next poll.",
            records.count(),
            TEAMSYKMELDING_NL_LEESAH_TOPIC,
            error
        )

        if (commitOnAllErrors) {
            logger.info("commitOnAllErrors is enabled, committing offsets despite replay batch error.")
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
            logger.info("Replay consumer for {} is already stopped", TEAMSYKMELDING_NL_LEESAH_TOPIC)
            return
        }

        logger.info("Stopping replay consumer for {}", TEAMSYKMELDING_NL_LEESAH_TOPIC)
        currentJob.cancel()
        currentConsumer?.wakeup()
        currentJob.join()
    }

    private fun closeKafkaConsumer(consumer: KafkaConsumer<String, String?>) {
        logger.info("Closing replay consumer for {}", TEAMSYKMELDING_NL_LEESAH_TOPIC)
        consumer.unsubscribe()
        consumer.close(CloseOptions.timeout(Duration.ofSeconds(CLOSE_DURATION_SECONDS)))
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LeesahNarmestelederReplayKafkaConsumer::class.java)
        private const val CONSUMER_JOB_DELAY_SECONDS = 30L
        private const val CLOSE_DURATION_SECONDS = 10L
        private const val POLL_DURATION_SECONDS = 1L
    }
}
