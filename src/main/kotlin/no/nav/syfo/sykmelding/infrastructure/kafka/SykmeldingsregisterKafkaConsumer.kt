package no.nav.syfo.sykmelding.infrastructure.kafka

import no.nav.syfo.platform.kafka.ConsumerRunner
import no.nav.syfo.platform.kafka.KafkaEnvironment
import no.nav.syfo.platform.kafka.KafkaListener
import no.nav.syfo.platform.kafka.consumerProperties
import no.nav.syfo.sykmelding.application.SENDT_SYKMELDING_TOPIC
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.Properties

class SykmeldingsregisterKafkaConsumer(
    consumerFactory: () -> Consumer<String, String?>,
) : KafkaListener {
    private val runner = ConsumerRunner(
        consumerFactory = consumerFactory,
        topics = listOf(SENDT_SYKMELDING_TOPIC),
        handler = {},
        coroutineName = "sykmeldingsregister",
    )

    override fun listen() = runner.start()

    override suspend fun stop() = runner.close()

    companion object {
        const val GROUP_ID = "esyfo-narmesteleder-sykmeldingsregister-consumer"

        fun kafkaConsumerProperties(env: KafkaEnvironment): Properties = consumerProperties(
            env = env,
            valueDeserializer = StringDeserializer::class,
            groupId = GROUP_ID,
        ).apply {
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100")
            put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "100")
        }
    }
}
