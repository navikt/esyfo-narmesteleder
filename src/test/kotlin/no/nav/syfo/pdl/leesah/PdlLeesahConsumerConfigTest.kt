package no.nav.syfo.pdl.leesah

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.kafka.KafkaEnvironment
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig

class PdlLeesahConsumerConfigTest :
    DescribeSpec({
        describe("kafkaConsumerProperties") {
            it("should configure dedicated PDL Leesah poll size without enabling auto commit") {
                val properties = PdlLeesahConsumer.kafkaConsumerProperties(KafkaEnvironment.createForLocal())

                properties[CommonClientConfigs.GROUP_ID_CONFIG] shouldBe PdlLeesahConsumer.PDL_LEESAH_CONSUMER_GROUP
                properties[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] shouldBe "500"
                properties[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] shouldBe "false"
            }
        }
    })
