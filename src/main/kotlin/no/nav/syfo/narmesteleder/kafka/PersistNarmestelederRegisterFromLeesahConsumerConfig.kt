package no.nav.syfo.narmesteleder.kafka

import no.nav.syfo.application.kafka.KafkaEnvironment
import no.nav.syfo.application.kafka.consumerProperties
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.Properties
import kotlin.time.Duration.Companion.minutes

const val LEESAH_NARMESTELEDER_REPLAY_GROUP_ID = "esyfo-narmesteleder-register-replay"

fun leesahNarmestelederReplayConsumerProperties(env: KafkaEnvironment): Properties = consumerProperties(
    env = env,
    valueDeserializer = StringDeserializer::class,
    groupId = LEESAH_NARMESTELEDER_REPLAY_GROUP_ID,
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
