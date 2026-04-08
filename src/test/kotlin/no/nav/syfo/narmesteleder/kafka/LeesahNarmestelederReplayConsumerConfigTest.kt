package no.nav.syfo.narmesteleder.kafka

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.kafka.KafkaEnvironment
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig

class LeesahNarmestelederReplayConsumerConfigTest :
    DescribeSpec({
        describe("leesahNarmestelederReplayConsumerProperties") {
            it("should configure dedicated replay consumer group from earliest offsets") {
                val properties = leesahNarmestelederReplayConsumerProperties(KafkaEnvironment.createForLocal())

                properties[CommonClientConfigs.GROUP_ID_CONFIG] shouldBe LEESAH_NARMESTELEDER_REPLAY_GROUP_ID
                properties[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] shouldBe "earliest"
                properties[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] shouldBe "500"
                properties[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] shouldBe "false"
                properties[ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG] shouldBe "500"
            }
        }
    })
