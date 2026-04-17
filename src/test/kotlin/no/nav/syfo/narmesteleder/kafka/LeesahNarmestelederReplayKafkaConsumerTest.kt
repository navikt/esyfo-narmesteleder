package no.nav.syfo.narmesteleder.kafka

import defaultLeesahKafkaMessage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.narmesteleder.service.NarmestelederRegisterService
import org.apache.kafka.clients.consumer.CloseOptions
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
        val consumer = PersistNarmestelederRegisterFromLeesahConsumer(
            handler = registerService,
            jacksonMapper = objectMapper,
            kafkaConsumer = kafkaConsumer,
            scope = kotlinx.coroutines.CoroutineScope(EmptyCoroutineContext),
            env = OtherEnvironmentProperties.createForLocal(),
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
                every { registerService.processLeesahBatch(capture(capturedRecords)) } returns emptyList()
                every { kafkaConsumer.commitSync() } returns Unit

                consumer.processBatch(records, kafkaConsumer)

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

                every { registerService.processLeesahBatch(any()) } throws IllegalStateException("boom")

                shouldThrow<IllegalStateException> {
                    consumer.processBatch(records, kafkaConsumer)
                }

                verify(exactly = 0) { kafkaConsumer.commitSync() }
            }
        }

        describe("stop") {
            it("should wake up, unsubscribe and close the consumer before returning") {
                val subscribeStarted = CompletableDeferred<Unit>()
                val pollReleased = CompletableDeferred<Unit>()

                every { kafkaConsumer.subscribe(any<List<String>>()) } answers {
                    subscribeStarted.complete(Unit)
                }
                every { kafkaConsumer.poll(any()) } answers {
                    runBlocking {
                        pollReleased.await()
                    }
                    throw org.apache.kafka.common.errors.WakeupException()
                }
                every { kafkaConsumer.wakeup() } answers {
                    pollReleased.complete(Unit)
                }
                every { kafkaConsumer.unsubscribe() } just Runs
                every { kafkaConsumer.close(any<CloseOptions>()) } returns Unit

                runTest {
                    consumer.listen()
                    subscribeStarted.await()
                    consumer.stop()
                }

                verify(exactly = 1) { kafkaConsumer.wakeup() }
                verify(exactly = 1) { kafkaConsumer.unsubscribe() }
                verify(exactly = 1) { kafkaConsumer.close(any<CloseOptions>()) }
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
    TEAMSYKMELDING_NL_LEESAH_TOPIC,
    0,
    offset,
    "key-$offset",
    value,
)
