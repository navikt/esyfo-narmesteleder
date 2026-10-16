package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.kafka.KafkaEnvironment
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig

class PersistNarmestelederRegisterFromLeesahConsumerConfigTest :
    FunSpec({
        context("leesahNarmestelederReplayConsumerProperties") {
            test("should configure dedicated replay consumer group from earliest offsets") {
                val properties = PersistNarmestelederRegisterFromLeesahConsumer.kafkaConsumerProperties(KafkaEnvironment.createForLocal())

                properties[CommonClientConfigs.GROUP_ID_CONFIG] shouldBe PersistNarmestelederRegisterFromLeesahConsumer.NARMESTELEDER_LEESAH_PERSIST_GROUP_ID
                properties[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] shouldBe "earliest"
                properties[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] shouldBe "500"
                properties[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] shouldBe "false"
                properties[ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG] shouldBe "500"
            }
        }
    })
