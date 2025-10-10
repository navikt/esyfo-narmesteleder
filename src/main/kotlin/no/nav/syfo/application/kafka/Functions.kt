package no.nav.syfo.application.kafka

import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException

// Extension function to add support for suspending poll and immediate cancellation handling
suspend fun <K, V> KafkaConsumer<K, V>.suspendingPoll(timeout: Duration): ConsumerRecords<K, V> {
    try {
        delay(timeout)
        return this.poll(java.time.Duration.ofMillis(0L))
    } catch (e: CancellationException) {
        try {
            this.wakeup()
        } catch (_: WakeupException) {
        }
        throw e
    }
}
