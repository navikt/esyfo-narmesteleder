package no.nav.syfo.narmesteleder.kafka

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.testcontainers.shaded.com.google.common.util.concurrent.SettableFuture
import java.time.LocalDateTime
import java.time.ZoneOffset

class NarmestelederLeesahProducerTest :
    DescribeSpec({
        val kafkaProducerMock = mockk<KafkaProducer<String, String?>>()
        val producer = NarmestelederLeesahProducer(kafkaProducerMock)

        beforeTest {
            clearAllMocks(currentThreadOnly = true)
        }

        describe("sendLeesahBatch") {
            it("publishes records with original key and value") {
                val futureMock = mockk<SettableFuture<RecordMetadata>>()
                coEvery { futureMock.get() } returns createRecordMetadata()
                coEvery { kafkaProducerMock.send(any<ProducerRecord<String, String?>>()) } returns futureMock

                producer.sendLeesahBatch(
                    listOf(
                        NarmestelederLeesahProducerRecord(
                            key = "original-key",
                            value = """{"field":"value"}""",
                        )
                    )
                )

                verify(exactly = 1) {
                    kafkaProducerMock.send(
                        withArg {
                            it.topic() shouldBe NarmestelederLeesahProducer.NARMESTELEDER_LEESAH_TOPIC
                            it.key() shouldBe "original-key"
                            it.value() shouldBe """{"field":"value"}"""
                        }
                    )
                }
                verify(exactly = 1) { futureMock.get() }
            }

            it("publishes tombstones as null values") {
                val futureMock = mockk<SettableFuture<RecordMetadata>>()
                coEvery { futureMock.get() } returns createRecordMetadata()
                coEvery { kafkaProducerMock.send(any<ProducerRecord<String, String?>>()) } returns futureMock

                producer.sendLeesahBatch(
                    listOf(
                        NarmestelederLeesahProducerRecord(
                            key = "tombstone-key",
                            value = null,
                        )
                    )
                )

                verify(exactly = 1) {
                    kafkaProducerMock.send(
                        withArg {
                            it.key() shouldBe "tombstone-key"
                            it.value() shouldBe null
                        }
                    )
                }
                verify(exactly = 1) { futureMock.get() }
            }

            it("propagates publish failures") {
                coEvery { kafkaProducerMock.send(any<ProducerRecord<String, String?>>()) } throws IllegalStateException("boom")

                shouldThrow<IllegalStateException> {
                    producer.sendLeesahBatch(
                        listOf(
                            NarmestelederLeesahProducerRecord(
                                key = "key",
                                value = "value",
                            )
                        )
                    )
                }
            }
        }
    })

private fun createRecordMetadata(): RecordMetadata = RecordMetadata(
    TopicPartition("topic", 0),
    0L,
    1,
    LocalDateTime.now().toEpochSecond(ZoneOffset.UTC),
    5,
    10,
)
