package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringSerializer
import kotlin.time.Duration.Companion.seconds

internal fun <V> fakeKafkaProducer(valueSerializer: Serializer<V>, autoComplete: Boolean = true): MockProducer<String, V> = MockProducer(autoComplete, null, StringSerializer(), valueSerializer)

/**
 * Runs [send] while the broker holds back the acknowledgement, then rejects the record.
 * Returns what [send] threw, or null if it returned without waiting for the acknowledgement.
 */
internal suspend fun MockProducer<*, *>.rejectAcknowledgement(error: RuntimeException, send: () -> Unit): Throwable? = coroutineScope {
    val sending = async(Dispatchers.IO) { runCatching(send).exceptionOrNull() }
    eventually(5.seconds) { history() shouldHaveSize 1 }
    errorNext(error).shouldBeTrue()
    sending.await()
}
