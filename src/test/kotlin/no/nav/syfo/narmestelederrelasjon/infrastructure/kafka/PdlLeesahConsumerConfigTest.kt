package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.kafka.KafkaEnvironment
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig

class PdlLeesahConsumerConfigTest :
    FunSpec({
        test("kafkaConsumerProperties keeps the consumer group and polls 500 records without auto commit") {
            val properties = PdlLeesahConsumer.kafkaConsumerProperties(KafkaEnvironment.createForLocal())

            properties[CommonClientConfigs.GROUP_ID_CONFIG] shouldBe "esyfo-narmesteleder-pdl-leesah"
            properties[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] shouldBe "500"
            properties[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] shouldBe "false"
        }
    })
