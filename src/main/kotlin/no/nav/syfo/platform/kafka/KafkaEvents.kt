package no.nav.syfo.platform.kafka

import no.nav.esyfo.observability.ApplicationLogger
import no.nav.esyfo.observability.Event
import no.nav.esyfo.observability.causeChain
import no.nav.esyfo.observability.createLogger
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.observability.EventContext
import no.nav.syfo.platform.observability.applicationEvent
import no.nav.syfo.platform.observability.logEvent
import org.slf4j.Logger
import org.slf4j.event.Level

internal enum class KafkaReason {
    DECODING,
    PROCESSING,
    MALFORMED_RECORD,
    INVALID_KEY,
    TOMBSTONE,
    UNSAFE_SKIP,
}

internal data class KafkaEventDetails(
    val consumer: KafkaEventConsumer,
    val reason: KafkaReason? = null,
    val retryDelaySeconds: Long? = null,
    val partition: Int? = null,
    val offset: Long? = null,
    val recordCount: Int? = null,
    val requestedCount: Int? = null,
    val missingCount: Int? = null,
    val errorCode: String? = null,
)

internal class KafkaEventLogger(private val logger: ApplicationLogger, private val consumer: KafkaEventConsumer) {
    constructor(logger: Logger, consumer: KafkaEventConsumer) : this(createLogger(logger), consumer)

    fun log(
        event: Event<EventContext<KafkaEventDetails>>,
        reason: KafkaReason? = null,
        cause: Throwable? = null,
        partition: Int? = null,
        offset: Long? = null,
        retryDelaySeconds: Long? = null,
        recordCount: Int? = null,
        requestedCount: Int? = null,
        missingCount: Int? = null,
        errorCode: String? = null,
    ) = logger.logEvent(
        event,
        KafkaEventDetails(
            consumer = consumer,
            reason = reason,
            retryDelaySeconds = retryDelaySeconds,
            partition = partition,
            offset = offset,
            recordCount = recordCount,
            requestedCount = requestedCount,
            missingCount = missingCount,
            errorCode = errorCode,
        ),
        cause = cause,
        // Use cases report upstream failures as a field rather than in the cause chain.
        upstreamFailure = cause?.causeChain()?.firstNotNullOfOrNull { (it as? ApiErrorException)?.upstreamFailure },
    )
}

private val kafkaFields: Map<String, (KafkaEventDetails) -> Any?> = mapOf(
    "consumer" to { it.consumer.name },
    "reason" to { it.reason?.name },
    "retry_delay_seconds" to { it.retryDelaySeconds },
    "partition" to { it.partition },
    "offset" to { it.offset },
    "record_count" to { it.recordCount },
    "requested_count" to { it.requestedCount },
    "missing_count" to { it.missingCount },
)

internal val kafkaConsumerFailed = applicationEvent<KafkaEventDetails>(
    name = "kafka_consumer_failed",
    level = Level.ERROR,
    message = "Kafka consumer failed; retrying consumption",
    upstream = "kafka",
    errorCode = { it.details.errorCode },
    fields = kafkaFields,
)

internal val kafkaConsumerCrashed = applicationEvent<KafkaEventDetails>(
    name = "kafka_consumer_crashed",
    level = Level.ERROR,
    message = "Kafka consumer stopped after an unrecoverable failure",
    upstream = "kafka",
    errorCode = { it.details.errorCode },
    fields = kafkaFields,
)

internal val kafkaBatchDiscarded = applicationEvent<KafkaEventDetails>(
    name = "kafka_batch_discarded",
    level = Level.ERROR,
    message = "Failed Kafka batch was committed because commitOnAllErrors is enabled",
    upstream = "kafka",
    errorCode = { it.details.errorCode },
    fields = kafkaFields,
)

internal val kafkaRecordSkippedError = applicationEvent<KafkaEventDetails>(
    name = "kafka_record_skipped_error",
    level = Level.ERROR,
    message = "Kafka record was skipped after a processing failure",
    upstream = "kafka",
    errorCode = { it.details.errorCode },
    fields = kafkaFields,
)

internal val kafkaRecordSkipped = applicationEvent<KafkaEventDetails>(
    name = "kafka_record_skipped",
    level = Level.WARN,
    message = "Kafka record was skipped",
    upstream = "kafka",
    errorCode = { it.details.errorCode },
    fields = kafkaFields,
)
