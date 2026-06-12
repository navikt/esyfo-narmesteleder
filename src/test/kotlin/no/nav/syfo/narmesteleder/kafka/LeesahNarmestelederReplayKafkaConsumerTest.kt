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
import no.nav.syfo.narmesteleder.service.LeesahBatchProcessResult
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
        val producer = mockk<NarmestelederLeesahProducer>(relaxed = true)
        val objectMapper = jacksonMapper()
        val consumer = PersistNarmestelederRegisterFromLeesahConsumer(
            handler = registerService,
            narmestelederLeesahProducer = producer,
            jacksonMapper = objectMapper,
            kafkaConsumer = kafkaConsumer,
            scope = kotlinx.coroutines.CoroutineScope(EmptyCoroutineContext),
            env = OtherEnvironmentProperties.createForLocal(),
        )

        beforeTest {
            clearMocks(kafkaConsumer, registerService, producer)
        }

        describe("processBatch") {
            it("should republish valid records with original key and value before committing offsets") {
                val validMessage = defaultLeesahKafkaMessage()
                val validValue = objectMapper.writeValueAsString(validMessage)
                val records = consumerRecords(
                    consumerRecord(
                        offset = 1L,
                        key = "original-key",
                        value = validValue,
                    ),
                    consumerRecord(
                        offset = 2L,
                        key = "malformed-key",
                        value = "{not-valid-json",
                    ),
                )
                val capturedRecords = slot<List<LeesahNarmestelederRecord>>()
                every { registerService.processLeesahBatchWithResult(capture(capturedRecords)) } returns LeesahBatchProcessResult(
                    insertedPersons = emptyList(),
                    validRecords = listOf(
                        LeesahNarmestelederRecord(
                            offset = 1L,
                            partition = 0,
                            message = validMessage,
                        )
                    )
                )
                every { producer.sendLeesahBatch(any()) } just Runs
                every { kafkaConsumer.commitSync() } returns Unit

                consumer.processBatch(records, kafkaConsumer)

                capturedRecords.captured.size shouldBe 1
                capturedRecords.captured.single().offset shouldBe 1L
                capturedRecords.captured.single().message.narmesteLederId shouldBe validMessage.narmesteLederId
                verify(exactly = 1) {
                    producer.sendLeesahBatch(
                        listOf(
                            NarmestelederLeesahProducerRecord(
                                key = "original-key",
                                value = validValue,
                            )
                        )
                    )
                }
                verify(exactly = 1) { kafkaConsumer.commitSync() }
            }

            it("should republish tombstones together with valid records") {
                val validMessage = defaultLeesahKafkaMessage()
                val validValue = objectMapper.writeValueAsString(validMessage)
                val records = consumerRecords(
                    consumerRecord(
                        offset = 1L,
                        key = "valid-key",
                        value = validValue,
                    ),
                    consumerRecord(
                        offset = 2L,
                        key = "tombstone-key",
                        value = null,
                    ),
                )

                every { registerService.processLeesahBatchWithResult(any()) } returns LeesahBatchProcessResult(
                    insertedPersons = emptyList(),
                    validRecords = listOf(
                        LeesahNarmestelederRecord(
                            offset = 1L,
                            partition = 0,
                            message = validMessage,
                        )
                    )
                )
                every { producer.sendLeesahBatch(any()) } just Runs
                every { kafkaConsumer.commitSync() } returns Unit

                consumer.processBatch(records, kafkaConsumer)

                verify(exactly = 1) {
                    producer.sendLeesahBatch(
                        listOf(
                            NarmestelederLeesahProducerRecord(
                                key = "valid-key",
                                value = validValue,
                            ),
                            NarmestelederLeesahProducerRecord(
                                key = "tombstone-key",
                                value = null,
                            ),
                        )
                    )
                }
                verify(exactly = 1) { kafkaConsumer.commitSync() }
            }

            it("should not republish malformed or invalid records") {
                val validMessage = defaultLeesahKafkaMessage()
                val validValue = objectMapper.writeValueAsString(validMessage)
                val invalidMessage = defaultLeesahKafkaMessage().copy(fnr = "123")
                val invalidValue = objectMapper.writeValueAsString(invalidMessage)
                val records = consumerRecords(
                    consumerRecord(
                        offset = 1L,
                        key = "valid-key",
                        value = validValue,
                    ),
                    consumerRecord(
                        offset = 2L,
                        key = "invalid-key",
                        value = invalidValue,
                    ),
                    consumerRecord(
                        offset = 3L,
                        key = "malformed-key",
                        value = "{not-valid-json",
                    ),
                )

                every { registerService.processLeesahBatchWithResult(any()) } returns LeesahBatchProcessResult(
                    insertedPersons = emptyList(),
                    validRecords = listOf(
                        LeesahNarmestelederRecord(
                            offset = 1L,
                            partition = 0,
                            message = validMessage,
                        )
                    )
                )
                every { producer.sendLeesahBatch(any()) } just Runs
                every { kafkaConsumer.commitSync() } returns Unit

                consumer.processBatch(records, kafkaConsumer)

                verify(exactly = 1) {
                    producer.sendLeesahBatch(
                        listOf(
                            NarmestelederLeesahProducerRecord(
                                key = "valid-key",
                                value = validValue,
                            )
                        )
                    )
                }
                verify(exactly = 1) { kafkaConsumer.commitSync() }
            }

            it("should not commit offsets when publish fails after persistence") {
                val records = consumerRecords(
                    consumerRecord(
                        offset = 1L,
                        key = "key-1",
                        value = objectMapper.writeValueAsString(defaultLeesahKafkaMessage()),
                    ),
                )

                every { registerService.processLeesahBatchWithResult(any()) } returns LeesahBatchProcessResult(
                    insertedPersons = emptyList(),
                    validRecords = listOf(
                        LeesahNarmestelederRecord(
                            offset = 1L,
                            partition = 0,
                            message = defaultLeesahKafkaMessage(),
                        )
                    )
                )
                every { producer.sendLeesahBatch(any()) } throws IllegalStateException("boom")

                shouldThrow<IllegalStateException> {
                    consumer.processBatch(records, kafkaConsumer)
                }

                verify(exactly = 1) { registerService.processLeesahBatchWithResult(any()) }
                verify(exactly = 1) { producer.sendLeesahBatch(any()) }
                verify(exactly = 0) { kafkaConsumer.commitSync() }
            }

            it("should not commit offsets when publish fails and commitOnAllErrors is enabled") {
                consumer.commitOnAllErrors = true
                val records = consumerRecords(
                    consumerRecord(
                        offset = 1L,
                        key = "key-1",
                        value = objectMapper.writeValueAsString(defaultLeesahKafkaMessage()),
                    ),
                )

                every { registerService.processLeesahBatchWithResult(any()) } returns LeesahBatchProcessResult(
                    insertedPersons = emptyList(),
                    validRecords = listOf(
                        LeesahNarmestelederRecord(
                            offset = 1L,
                            partition = 0,
                            message = defaultLeesahKafkaMessage(),
                        )
                    )
                )
                every { producer.sendLeesahBatch(any()) } throws IllegalStateException("boom")

                shouldThrow<IllegalStateException> {
                    consumer.processBatch(records, kafkaConsumer)
                }

                verify(exactly = 1) { registerService.processLeesahBatchWithResult(any()) }
                verify(exactly = 1) { producer.sendLeesahBatch(any()) }
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
    key: String = "key-$offset",
    value: String?,
): ConsumerRecord<String, String?> = ConsumerRecord(
    TEAMSYKMELDING_NL_LEESAH_TOPIC,
    0,
    offset,
    key,
    value,
)
