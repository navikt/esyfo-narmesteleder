package no.nav.syfo.platform.kafka

import no.nav.syfo.platform.observability.applicationEvent
import org.slf4j.event.Level

internal object KafkaConsumerLogEvents {
    val shutdownTimedOut = applicationEvent<TimeoutContext>(
        name = "kafka_consumer.shutdown.timed_out",
        level = Level.WARN,
        message = "Consumer did not stop within timeout",
        operation = "kafka_consumer.shutdown",
        errorCode = { "CONSUMER_SHUTDOWN_TIMEOUT" },
        fields = mapOf("timeout_seconds" to { it.timeoutSeconds }),
    )

    val fatalFailure = applicationEvent<Unit>(
        name = "kafka_consumer.fatal_failure",
        level = Level.ERROR,
        message = "Consumer hit a fatal error and will not restart",
        operation = "kafka_consumer.poll",
        errorCode = { "CONSUMER_FATAL_FAILURE" },
    )

    val restart = applicationEvent<BackoffContext>(
        name = "kafka_consumer.restart",
        level = Level.WARN,
        message = "Consumer failed, restarting after backoff",
        operation = "kafka_consumer.poll",
        errorCode = { "CONSUMER_TRANSIENT_FAILURE" },
        fields = mapOf("backoff_millis" to { it.backoffMillis }),
    )
}

internal data class TimeoutContext(val timeoutSeconds: Long)

internal data class BackoffContext(val backoffMillis: Long)
