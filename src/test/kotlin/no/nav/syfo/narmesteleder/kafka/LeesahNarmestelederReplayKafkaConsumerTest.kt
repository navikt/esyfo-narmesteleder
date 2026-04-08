package no.nav.syfo.narmesteleder.kafka

import defaultLeesahKafkaMessage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.narmesteleder.service.NarmestelederRegisterService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import kotlin.coroutines.EmptyCoroutineContext

class LeesahNarmestelederReplayKafkaConsumerTest :
    DescribeSpec({
        val kafkaConsumer = mockk<KafkaConsumer<String, String?>>(relaxed = true)
        val registerService = mockk<NarmestelederRegisterService>()
        val objectMapper = jacksonMapper()
        val consumer = LeesahNarmestelederReplayKafkaConsumer(
            handler = registerService,
            jacksonMapper = objectMapper,
            kafkaConsumer = kafkaConsumer,
            scope = kotlinx.coroutines.CoroutineScope(EmptyCoroutineContext),
        )

        beforeTest {
            clearMocks(kafkaConsumer, registerService)
        }

        describe("processBatch") {
            it("should skip malformed records and commit offsets after the batch") {
                val validMessage = defaultLeesahKafkaMessage()
                val records = consumerRecords(
                    consumerRecord(
                        offset = 1L,
                        value = objectMapper.writeValueAsString(validMessage),
                    ),
                    consumerRecord(
                        offset = 2L,
                        value = "{not-valid-json",
                    ),
                )
                val capturedRecords = slot<List<LeesahNarmestelederRecord>>()

                every { registerService.upsertBatch(capture(capturedRecords)) } returns Unit
                every { kafkaConsumer.commitSync() } returns Unit

                consumer.processBatch(records)

                capturedRecords.captured.size shouldBe 1
                capturedRecords.captured.single().message.narmesteLederId shouldBe validMessage.narmesteLederId
                verify(exactly = 1) { kafkaConsumer.commitSync() }
            }

            it("should not commit offsets when batch handling fails") {
                val records = consumerRecords(
                    consumerRecord(
                        offset = 1L,
                        value = objectMapper.writeValueAsString(defaultLeesahKafkaMessage()),
                    ),
                )

                every { registerService.upsertBatch(any()) } throws IllegalStateException("boom")

                shouldThrow<IllegalStateException> {
                    consumer.processBatch(records)
                }

                verify(exactly = 0) { kafkaConsumer.commitSync() }
            }
        }
    })

private fun consumerRecords(vararg records: ConsumerRecord<String, String?>): ConsumerRecords<String, String?> = ConsumerRecords(
    records.groupBy { TopicPartition(it.topic(), it.partition()) }
)

private fun consumerRecord(
    offset: Long,
    value: String?,
): ConsumerRecord<String, String?> = ConsumerRecord(
    SYKMELDING_NL_TOPIC,
    0,
    offset,
    "key-$offset",
    value,
)
