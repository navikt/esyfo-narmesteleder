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
import no.nav.syfo.application.kafka.KafkaEnvironment
import no.nav.syfo.application.kafka.KafkaListener
import no.nav.syfo.application.kafka.consumerProperties
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import no.nav.syfo.narmesteleder.service.NarmestelederRegisterService
import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Properties
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class LeesahNarmestelederRecord(
    val offset: Long,
    val partition: Int = 0,
    val message: NarmestelederLeesahKafkaMessage,
)

private data class LeesahRecordId(
    val partition: Int,
    val offset: Long,
)

private sealed interface LeesahRepublishCandidate {
    val producerRecord: NarmestelederLeesahProducerRecord

    data class Parsed(
        val id: LeesahRecordId,
        override val producerRecord: NarmestelederLeesahProducerRecord,
    ) : LeesahRepublishCandidate

    data class Tombstone(
        override val producerRecord: NarmestelederLeesahProducerRecord,
    ) : LeesahRepublishCandidate
}

private data class LeesahDeserializationResult(
    val parsedRecords: List<LeesahNarmestelederRecord>,
    val republishCandidates: List<LeesahRepublishCandidate>,
)

class PersistNarmestelederRegisterFromLeesahConsumer(
    private val handler: NarmestelederRegisterService,
    private val narmestelederLeesahProducer: NarmestelederLeesahProducer,
    private val jacksonMapper: ObjectMapper,
    private val kafkaConsumerFactory: () -> KafkaConsumer<String, String?>,
    private val scope: CoroutineScope,
    private val env: OtherEnvironmentProperties,
) : KafkaListener,
    AutoCloseable {
    constructor(
        handler: NarmestelederRegisterService,
        narmestelederLeesahProducer: NarmestelederLeesahProducer,
        jacksonMapper: ObjectMapper,
        kafkaConsumer: KafkaConsumer<String, String?>,
        scope: CoroutineScope,
        env: OtherEnvironmentProperties,
    ) : this(
        handler = handler,
        narmestelederLeesahProducer = narmestelederLeesahProducer,
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
            logger.info(
                "Persisting of narmesteleder from leesah topic is disabled, not starting {} consumer for {}",
                this::class.simpleName,
                TEAMSYKMELDING_NL_LEESAH_TOPIC
            )
            return
        }
        if (job?.isActive == true) {
            logger.info("{} consumer for {} is already running", this::class.simpleName, TEAMSYKMELDING_NL_LEESAH_TOPIC)
            return
        }

        val consumer = kafkaConsumerFactory()
        kafkaConsumer = consumer

        logger.info("Starting {} consumer for {}", this::class.simpleName, TEAMSYKMELDING_NL_LEESAH_TOPIC)
        job = scope.launch(Dispatchers.IO + CoroutineName("${this::class.simpleName}-coroutine")) {
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
                logger.info("Exited {} consumer loop for {}", this::class.simpleName, TEAMSYKMELDING_NL_LEESAH_TOPIC)
            }
        }
    }

    private fun deserializeRecords(records: ConsumerRecords<String, String?>): LeesahDeserializationResult {
        val parsedRecords = mutableListOf<LeesahNarmestelederRecord>()
        val republishCandidates = mutableListOf<LeesahRepublishCandidate>()

        records.forEach { record ->
            try {
                val value = record.value()
                val producerRecord = NarmestelederLeesahProducerRecord(
                    key = record.key(),
                    value = value,
                )

                if (value == null) {
                    republishCandidates.add(
                        LeesahRepublishCandidate.Tombstone(
                            producerRecord = producerRecord,
                        )
                    )
                } else {
                    val parsedRecord = LeesahNarmestelederRecord(
                        offset = record.offset(),
                        partition = record.partition(),
                        message = jacksonMapper.readValue<NarmestelederLeesahKafkaMessage>(value),
                    )
                    parsedRecords.add(parsedRecord)
                    republishCandidates.add(
                        LeesahRepublishCandidate.Parsed(
                            id = LeesahRecordId(
                                partition = record.partition(),
                                offset = record.offset(),
                            ),
                            producerRecord = producerRecord,
                        )
                    )
                }
            } catch (e: JsonProcessingException) {
                logger.warn(
                    "Error while deserializing record from {} at offset {}. Skipping record.",
                    record.topic(),
                    record.offset(),
                    e
                )
            }
        }

        return LeesahDeserializationResult(
            parsedRecords = parsedRecords,
            republishCandidates = republishCandidates,
        )
    }

    internal fun processBatch(
        records: ConsumerRecords<String, String?>,
        kafkaConsumer: KafkaConsumer<String, String?> = requireNotNull(this.kafkaConsumer) {
            "${this::class.simpleName} consumer is not started"
        },
    ) {
        val recordsToRepublish = runCatching {
            val deserializedRecords = deserializeRecords(records)
            val batchResult = handler.processLeesahBatchWithResult(deserializedRecords.parsedRecords)
            val validRecordIds = batchResult.validRecords.mapTo(mutableSetOf()) {
                LeesahRecordId(
                    partition = it.partition,
                    offset = it.offset,
                )
            }
            val recordsToRepublish = deserializedRecords.republishCandidates.mapNotNull { candidate ->
                when (candidate) {
                    is LeesahRepublishCandidate.Parsed -> {
                        candidate.producerRecord.takeIf { candidate.id in validRecordIds }
                    }

                    is LeesahRepublishCandidate.Tombstone -> candidate.producerRecord
                }
            }
            recordsToRepublish
        }.getOrElse { error ->
            handleBatchError(records, kafkaConsumer, error)
            return
        }

        try {
            // Publish happens after persistence and before commit. If publishing fails after a partial batch
            // has been sent, the whole batch will be retried and downstream consumers must tolerate duplicates.
            narmestelederLeesahProducer.sendLeesahBatch(recordsToRepublish)
            kafkaConsumer.commitSync()
        } catch (error: Throwable) {
            logger.error(
                "Error while republishing {} records to {}. Offsets will not be committed.",
                recordsToRepublish.size,
                NarmestelederLeesahProducer.NARMESTELEDER_LEESAH_TOPIC,
                error,
            )
            throw error
        }
    }

    private fun handleBatchError(
        records: ConsumerRecords<String, String?>,
        kafkaConsumer: KafkaConsumer<String, String?>,
        error: Throwable,
    ) {
        logger.error(
            "Error while processing batch of {} records from {}. Entire batch will be retried on next poll.",
            records.count(),
            TEAMSYKMELDING_NL_LEESAH_TOPIC,
            error
        )

        if (commitOnAllErrors) {
            logger.info("commitOnAllErrors is enabled, committing offsets despite batch error.")
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
            logger.info("{} consumer for {} is already stopped", this::class.simpleName, TEAMSYKMELDING_NL_LEESAH_TOPIC)
            return
        }

        logger.info("Stopping {} consumer for {}", this::class.simpleName, TEAMSYKMELDING_NL_LEESAH_TOPIC)
        currentJob.cancel()
        currentConsumer?.wakeup()
        currentJob.join()
    }

    private fun closeKafkaConsumer(consumer: KafkaConsumer<String, String?>) {
        logger.info("Closing {} consumer for {}", this::class.simpleName, TEAMSYKMELDING_NL_LEESAH_TOPIC)
        consumer.unsubscribe()
        consumer.close(CloseOptions.timeout(Duration.ofSeconds(CLOSE_DURATION_SECONDS)))
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PersistNarmestelederRegisterFromLeesahConsumer::class.java)
        private const val CONSUMER_JOB_DELAY_SECONDS = 30L
        private const val CLOSE_DURATION_SECONDS = 10L
        private const val POLL_DURATION_SECONDS = 1L
        val NARMESTELEDER_LEESAH_PERSIST_GROUP_ID = "esyfo-narmesteleder-leesah-persist-consumer"
        fun kafkaConsumerProperties(env: KafkaEnvironment): Properties = consumerProperties(
            env = env,
            valueDeserializer = StringDeserializer::class,
            groupId = NARMESTELEDER_LEESAH_PERSIST_GROUP_ID,
        ).apply {
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500")
            put(
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                5.minutes.inWholeMilliseconds.toString()
            )
            put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, "1048576")
            put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "500")
        }
    }
}
