package no.nav.syfo.pdl.leesah

import io.micrometer.core.instrument.Counter
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nav.person.pdl.leesah.Personhendelse
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.kafka.KafkaEnvironment
import no.nav.syfo.application.kafka.KafkaListener
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.util.logger
import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.RecordDeserializationException
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.errors.WakeupException
import java.time.Duration
import java.util.Properties
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class PdlLeesahConsumer(
    private val kafkaConsumerFactory: () -> KafkaConsumer<String, Personhendelse>,
    private val scope: CoroutineScope,
    private val env: OtherEnvironmentProperties,
    private val pdlLeesahNameUpdateService: PdlLeesahNameUpdateService,
    private val pollDuration: Duration = Duration.ofSeconds(POLL_DURATION_SECONDS),
    private val retryDelaySeconds: Long = CONSUMER_JOB_DELAY_SECONDS,
) : KafkaListener,
    AutoCloseable {
    constructor(
        kafkaConsumer: KafkaConsumer<String, Personhendelse>,
        scope: CoroutineScope,
        env: OtherEnvironmentProperties,
        pdlLeesahNameUpdateService: PdlLeesahNameUpdateService,
        pollDuration: Duration = Duration.ofSeconds(POLL_DURATION_SECONDS),
        retryDelaySeconds: Long = CONSUMER_JOB_DELAY_SECONDS,
    ) : this(
        kafkaConsumerFactory = { kafkaConsumer },
        scope = scope,
        env = env,
        pdlLeesahNameUpdateService = pdlLeesahNameUpdateService,
        pollDuration = pollDuration,
        retryDelaySeconds = retryDelaySeconds,
    )

    private var job: Job? = null

    @Volatile
    private var kafkaConsumer: KafkaConsumer<String, Personhendelse>? = null

    override fun listen() {
        // Defensive guard: RunningTasks already avoids creating this consumer when disabled,
        // but keep the check here for direct usage in tests or other callers outside that setup.
        if (!env.pdlLeesahConsumerEnabled) {
            logger.info("PDL Leesah consumer is disabled, not starting consumer for {}", PDL_LEESAH_TOPIC)
            return
        }

        if (job?.isActive == true) {
            logger.info("{} consumer for {} is already running", this::class.simpleName, PDL_LEESAH_TOPIC)
            return
        }

        val consumer = kafkaConsumerFactory()
        kafkaConsumer = consumer

        logger.info("Starting {} consumer for {}", this::class.simpleName, PDL_LEESAH_TOPIC)
        job = scope.launch(Dispatchers.IO + CoroutineName("${this::class.simpleName}-coroutine")) {
            try {
                consumer.subscribe(listOf(PDL_LEESAH_TOPIC))

                while (isActive) {
                    try {
                        pollAndProcess(consumer)
                    } catch (_: WakeupException) {
                        logger.info("Wakeup received for {}", PDL_LEESAH_TOPIC)
                        break
                    } catch (exception: FatalPdlLeesahConsumerException) {
                        logger.error(
                            "Stopping {} consumer for {} after failing to handle deserialization error deterministically. exceptionType={}",
                            this@PdlLeesahConsumer::class.simpleName,
                            PDL_LEESAH_TOPIC,
                            exception.cause?.javaClass?.simpleName ?: exception::class.simpleName,
                        )
                        break
                    } catch (_: CancellationException) {
                        break
                    } catch (exception: Exception) {
                        logger.error(
                            "Unexpected error in {} for {}. Will retry in {} seconds. exceptionType={}",
                            this@PdlLeesahConsumer::class.simpleName,
                            PDL_LEESAH_TOPIC,
                            retryDelaySeconds,
                            exception::class.simpleName,
                        )
                        consumer.unsubscribe()
                        delay(retryDelaySeconds.seconds)
                        consumer.subscribe(listOf(PDL_LEESAH_TOPIC))
                    }
                }
            } finally {
                closeKafkaConsumer(consumer)
                if (kafkaConsumer === consumer) {
                    kafkaConsumer = null
                }
                job = null
                logger.info("Exited {} consumer loop for {}", this::class.simpleName, PDL_LEESAH_TOPIC)
            }
        }
    }

    internal suspend fun pollAndProcess(
        kafkaConsumer: KafkaConsumer<String, Personhendelse> = requireNotNull(this.kafkaConsumer) {
            "${this::class.simpleName} consumer is not started"
        },
    ) {
        try {
            val records = kafkaConsumer.poll(pollDuration)
            if (!records.isEmpty) {
                processRecords(records, kafkaConsumer)
            }
        } catch (exception: RecordDeserializationException) {
            handleRecordDeserializationException(kafkaConsumer, exception)
        } catch (exception: SerializationException) {
            incrementMetric(
                opplysningstype = METRIC_UNKNOWN_VALUE,
                endringstype = METRIC_UNKNOWN_VALUE,
                result = RESULT_SERIALIZATION_ERROR,
            )
            // Do not log exception.message or Throwable here; deserialization failures may include
            // persondata or raw message content from the poison-pill payload.
            logger.error(
                "Serialization error while polling {}. Will retry in {} seconds. exceptionType={}",
                PDL_LEESAH_TOPIC,
                retryDelaySeconds,
                exception::class.simpleName,
            )
            delay(retryDelaySeconds.seconds)
        }
    }

    internal suspend fun processRecords(
        records: ConsumerRecords<String, Personhendelse>,
        kafkaConsumer: KafkaConsumer<String, Personhendelse> = requireNotNull(this.kafkaConsumer) {
            "${this::class.simpleName} consumer is not started"
        },
    ) {
        val processedOffsets = mutableMapOf<TopicPartition, OffsetAndMetadata>()
        val relevantRecords = mutableListOf<RecordProcessingResult.RelevantNameRecord>()
        val bufferedEventMetrics = mutableListOf<BufferedLeesahEventMetric>()
        records.forEach { record ->
            processedOffsets[TopicPartition(record.topic(), record.partition())] = OffsetAndMetadata(record.offset() + 1)
            when (val classifiedRecord = classifyRecord(record)) {
                is RecordProcessingResult.Metrics -> bufferedEventMetrics.add(classifiedRecord.bufferedEventMetric)
                is RecordProcessingResult.RelevantNameRecord -> relevantRecords.add(classifiedRecord)
            }
        }

        val updateResult = if (relevantRecords.isNotEmpty()) {
            pdlLeesahNameUpdateService.processNameChanges(relevantRecords.flatMap(RecordProcessingResult.RelevantNameRecord::personidenter))
        } else {
            null
        }

        relevantRecords.groupBy { it.metricKey }
            .forEach { (metricKey, groupedRecords) ->
                bufferedEventMetrics.add(
                    BufferedLeesahEventMetric(
                        opplysningstype = metricKey.opplysningstype,
                        endringstype = metricKey.endringstype,
                        result = RESULT_PROCESSED,
                        count = groupedRecords.size,
                    )
                )
            }

        if (processedOffsets.isNotEmpty()) {
            kafkaConsumer.commitSync(processedOffsets)
        }

        updateResult?.let { result ->
            logger.info(
                "Processed PDL Leesah name event batch relevantRecordCount={}, updatedCount={}, notFoundInRegisterCount={}, pdlNotFoundCount={}",
                relevantRecords.size,
                result.updatedCount,
                result.notFoundInRegisterCount,
                result.pdlNotFoundCount,
            )
        }

        emitBufferedEventMetrics(bufferedEventMetrics)
        updateResult?.emitPersonUpdateMetrics()
    }

    private fun handleRecordDeserializationException(
        kafkaConsumer: KafkaConsumer<String, Personhendelse>,
        exception: RecordDeserializationException,
    ) {
        val nextOffset = exception.offset() + 1
        val topicPartition = exception.topicPartition()

        try {
            kafkaConsumer.seek(topicPartition, nextOffset)
            kafkaConsumer.commitSync(mapOf(topicPartition to OffsetAndMetadata(nextOffset)))
        } catch (seekOrCommitException: Exception) {
            throw FatalPdlLeesahConsumerException(seekOrCommitException)
        }

        incrementMetric(
            opplysningstype = METRIC_UNKNOWN_VALUE,
            endringstype = METRIC_UNKNOWN_VALUE,
            result = RESULT_RECORD_DESERIALIZATION_SKIPPED,
        )
        // Do not log exception.message or Throwable here; it can contain persondata or raw payload bytes.
        logger.error(
            "Skipped poison-pill record after deserialization failure for topic={}, partition={}, offset={}, exceptionType={}",
            topicPartition.topic(),
            topicPartition.partition(),
            exception.offset(),
            exception::class.simpleName,
        )
    }

    private fun classifyRecord(record: ConsumerRecord<String, Personhendelse>): RecordProcessingResult {
        val personhendelse = record.value()
        if (personhendelse == null) {
            logger.warn("Skipping tombstone record from {}", PDL_LEESAH_TOPIC)
            return RecordProcessingResult.Metrics(
                bufferedEventMetric = BufferedLeesahEventMetric(
                    opplysningstype = METRIC_UNKNOWN_VALUE,
                    endringstype = METRIC_UNKNOWN_VALUE,
                    result = RESULT_TOMBSTONE,
                )
            )
        }

        val opplysningstype = personhendelse.opplysningstype ?: METRIC_UNKNOWN_VALUE
        val endringstype = personhendelse.endringstype?.toString() ?: METRIC_UNKNOWN_VALUE

        if (!isRelevantNameEvent(personhendelse)) {
            logger.info(
                "Ignoring PDL Leesah event opplysningstype={}, endringstype={}",
                opplysningstype,
                endringstype,
            )
            return RecordProcessingResult.Metrics(
                bufferedEventMetric = BufferedLeesahEventMetric(
                    opplysningstype = opplysningstype,
                    endringstype = endringstype,
                    result = RESULT_IGNORED,
                )
            )
        }

        return RecordProcessingResult.RelevantNameRecord(
            personidenter = personhendelse.personidenter.orEmpty(),
            metricKey = RelevantNameEventMetricKey(
                opplysningstype = opplysningstype,
                endringstype = endringstype,
            ),
        )
    }

    private fun isRelevantNameEvent(personhendelse: Personhendelse): Boolean = personhendelse.opplysningstype == NAVN_OPPLYSNINGSTYPE && personhendelse.navn != null

    private fun emitBufferedEventMetrics(bufferedMetrics: List<BufferedLeesahEventMetric>) {
        bufferedMetrics.forEach { metric ->
            incrementMetric(
                opplysningstype = metric.opplysningstype,
                endringstype = metric.endringstype,
                result = metric.result,
                count = metric.count,
            )
        }
    }

    private fun incrementMetric(
        opplysningstype: String,
        endringstype: String,
        result: String,
        count: Int = 1,
    ) {
        if (count <= 0) {
            return
        }

        Counter.builder(PDL_LEESAH_HENDELSE_TOTAL)
            .description("Counts processed PDL Leesah events without logging persondata")
            .tag("opplysningstype", opplysningstype)
            .tag("endringstype", endringstype)
            .tag("result", result)
            .register(METRICS_REGISTRY)
            .increment(count.toDouble())
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
            logger.info("{} consumer for {} is already stopped", this::class.simpleName, PDL_LEESAH_TOPIC)
            return
        }

        logger.info("Stopping {} consumer for {}", this::class.simpleName, PDL_LEESAH_TOPIC)
        currentJob.cancel()
        currentConsumer?.wakeup()
        currentJob.join()
    }

    private fun closeKafkaConsumer(consumer: KafkaConsumer<String, Personhendelse>) {
        logger.info("Closing {} consumer for {}", this::class.simpleName, PDL_LEESAH_TOPIC)
        consumer.unsubscribe()
        consumer.close(CloseOptions.timeout(Duration.ofSeconds(CLOSE_DURATION_SECONDS)))
    }

    companion object {
        private val logger = logger()
        private const val CONSUMER_JOB_DELAY_SECONDS = 30L
        private const val CLOSE_DURATION_SECONDS = 10L
        private const val POLL_DURATION_SECONDS = 1L
        private const val MAX_POLL_RECORDS = "500"
        internal const val PDL_LEESAH_TOPIC = "pdl.leesah-v1"
        internal const val PDL_LEESAH_CONSUMER_GROUP = "esyfo-narmesteleder-pdl-leesah"
        internal const val NAVN_OPPLYSNINGSTYPE = "NAVN_V1"
        internal const val PDL_LEESAH_HENDELSE_TOTAL = "${METRICS_NS}_pdl_leesah_hendelse_total"
        internal const val RESULT_IGNORED = "ignored"
        internal const val RESULT_PROCESSED = "processed"
        internal const val RESULT_SERIALIZATION_ERROR = "serialization_error"
        internal const val RESULT_RECORD_DESERIALIZATION_SKIPPED = "record_deserialization_skipped"
        internal const val RESULT_TOMBSTONE = "tombstone"
        internal const val METRIC_UNKNOWN_VALUE = "unknown"

        fun kafkaConsumerProperties(env: KafkaEnvironment): Properties = no.nav.syfo.application.kafka.avroConsumerProperties(
            groupId = PDL_LEESAH_CONSUMER_GROUP,
            env = env,
        ).apply {
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_POLL_RECORDS)
        }
    }
}

private class FatalPdlLeesahConsumerException(
    cause: Throwable,
) : RuntimeException(null, cause, false, false)

private data class RelevantNameEventMetricKey(
    val opplysningstype: String,
    val endringstype: String,
)

private sealed interface RecordProcessingResult {
    data class RelevantNameRecord(
        val personidenter: List<String>,
        val metricKey: RelevantNameEventMetricKey,
    ) : RecordProcessingResult

    data class Metrics(
        val bufferedEventMetric: BufferedLeesahEventMetric,
    ) : RecordProcessingResult
}

private data class BufferedLeesahEventMetric(
    val opplysningstype: String,
    val endringstype: String,
    val result: String,
    val count: Int = 1,
)

private fun PdlLeesahNameUpdateResult.emitPersonUpdateMetrics() {
    countPdlLeesahPersonUpdate(PdlLeesahNameUpdateService.RESULT_UPDATED, updatedCount)
    countPdlLeesahPersonUpdate(PdlLeesahNameUpdateService.RESULT_NOT_FOUND_IN_REGISTER, notFoundInRegisterCount)
    countPdlLeesahPersonUpdate(PdlLeesahNameUpdateService.RESULT_PDL_NOT_FOUND, pdlNotFoundCount)
}
