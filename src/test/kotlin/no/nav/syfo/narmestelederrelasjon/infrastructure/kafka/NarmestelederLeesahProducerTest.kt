package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederLeesahProducer.Companion.NARMESTELEDER_LEESAH_TOPIC
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import java.util.concurrent.ExecutionException

class NarmestelederLeesahProducerTest :
    FunSpec({
        fun fakeLeesahProducer(autoComplete: Boolean = true) = fakeKafkaProducer<String?>(StringSerializer(), autoComplete)

        context("sendLeesahBatch") {
            test("publishes records with original key and value") {
                val kafkaProducer = fakeLeesahProducer()

                NarmestelederLeesahProducer(kafkaProducer).sendLeesahBatch(
                    listOf(NarmestelederLeesahProducerRecord(key = "original-key", value = """{"field":"value"}"""))
                )

                kafkaProducer.history() shouldBe listOf(
                    ProducerRecord(NARMESTELEDER_LEESAH_TOPIC, "original-key", """{"field":"value"}""")
                )
            }

            test("publishes tombstones as null values") {
                val kafkaProducer = fakeLeesahProducer()

                NarmestelederLeesahProducer(kafkaProducer).sendLeesahBatch(
                    listOf(NarmestelederLeesahProducerRecord(key = "tombstone-key", value = null))
                )

                kafkaProducer.history() shouldBe listOf(
                    ProducerRecord<String, String?>(NARMESTELEDER_LEESAH_TOPIC, "tombstone-key", null)
                )
            }

            test("propagates publish failures") {
                val kafkaProducer = fakeLeesahProducer().apply { sendException = IllegalStateException("boom") }

                shouldThrow<IllegalStateException> {
                    NarmestelederLeesahProducer(kafkaProducer).sendLeesahBatch(
                        listOf(NarmestelederLeesahProducerRecord(key = "key", value = "value"))
                    )
                }
            }

            test("waits for the broker to acknowledge each record") {
                val kafkaProducer = fakeLeesahProducer(autoComplete = false)
                val rejection = IllegalStateException("rejected by broker")

                val failure = kafkaProducer.rejectAcknowledgement(rejection) {
                    NarmestelederLeesahProducer(kafkaProducer).sendLeesahBatch(
                        listOf(NarmestelederLeesahProducerRecord(key = "key", value = "value"))
                    )
                }

                failure.shouldBeInstanceOf<ExecutionException>().cause shouldBe rejection
            }
        }
    })
